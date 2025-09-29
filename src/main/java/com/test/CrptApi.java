package com.test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CrptApi implements AutoCloseable {
    private static final String BASE_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final AtomicInteger currentCount;
    private final ScheduledExecutorService scheduler;
    private final int requestLimit;
    private final AuthManager authManager;
    private volatile Instant tokenExpiration = Instant.now().minusSeconds(36000L);

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be positive");
        if (timeUnit == null) throw new IllegalArgumentException("timeUnit must not be null");
        this.requestLimit = requestLimit;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
        this.semaphore = new Semaphore(requestLimit, true);
        this.currentCount = new AtomicInteger(0);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.authManager = new AuthManager(httpClient, objectMapper);
        long periodSeconds = timeUnit.toSeconds(1);
        scheduler.scheduleAtFixedRate(this::resetLimiter, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    private void resetLimiter() {
        currentCount.set(0);
        int toRelease = requestLimit - semaphore.availablePermits();
        if (toRelease > 0) semaphore.release(toRelease);
    }

    public String createIntroduceDoc(IntroduceGoodsDoc doc, String signature) {
        Objects.requireNonNull(doc, "Document must not be null");
        Objects.requireNonNull(signature, "Signature must not be null");
        doc.validate();

        try {
            semaphore.acquire();
            currentCount.incrementAndGet();
            try {
                if (tokenExpiration.isBefore(Instant.now())) {
                    Map<String, String> authKey = authManager.getAuthKey();
                    String uuid = authKey.get("uuid");
                    String data = authKey.get("data");
                    String signedData = signData(data);//*
                    authManager.authenticate(uuid, signedData);
                    tokenExpiration = Instant.now().plusSeconds(36000L);
                }

                String productDocumentJson = objectMapper.writeValueAsString(doc.getContent());
                Map<String, String> requestBody = Map.of(
                        "document_format", doc.getDocumentFormat(),
                        "product_document", productDocumentJson,
                        "signature", signature,
                        "type", doc.getType()
                );

                String json = objectMapper.writeValueAsString(requestBody);
                String encodedPg = URLEncoder.encode(doc.getProductGroup(), StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "?pg=" + encodedPg))
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .header("Authorization", "Bearer " + authManager.getToken())
                        .header("Accept", "*/*")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body();
                else if (response.statusCode() == 401) throw new CrptApiException("Authentication error (XML): " + response.body());
                else if (response.statusCode() == 406) throw new CrptApiException("Not Acceptable: Invalid Accept header");
                else throw new CrptApiException("API error " + response.statusCode() + ": " + response.body());
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrptApiException("Interrupted while acquiring semaphore", e);
        } catch (Exception e) {
            throw new CrptApiException("Failed to create document", e);
        }
    }

    private String signData(String data) {
        return data; //*
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class AuthManager {
        private static final String AUTH_KEY_URL = "https://ismp.crpt.ru/api/v3/auth/cert/key";
        private static final String AUTH_TOKEN_URL = "https://ismp.crpt.ru/api/v3/auth/cert/";
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;
        private volatile String token;

        public AuthManager(HttpClient httpClient, ObjectMapper objectMapper) {
            this.httpClient = httpClient;
            this.objectMapper = objectMapper;
        }

        public Map<String, String> getAuthKey() {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(AUTH_KEY_URL))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return objectMapper.readValue(response.body(), Map.class);
                throw new CrptApiException("Auth key error " + response.statusCode() + ": " + response.body());
            } catch (Exception e) {
                throw new CrptApiException("Failed to get auth key", e);
            }
        }

        public String authenticate(String uuid, String signedData) {
            Objects.requireNonNull(uuid, "UUID must not be null");
            Objects.requireNonNull(signedData, "Signed data must not be null");
            try {
                Map<String, String> body = Map.of("uuid", uuid, "data", signedData);
                String json = objectMapper.writeValueAsString(body);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(AUTH_TOKEN_URL))
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Map<String, String> responseMap = objectMapper.readValue(response.body(), Map.class);
                    String newToken = responseMap.get("token");
                    if (newToken != null) {
                        this.token = newToken;
                        return newToken;
                    }
                    throw new CrptApiException("No token in response: " + response.body());
                }
                throw new CrptApiException("Auth error " + response.statusCode() + ": " + response.body());
            } catch (Exception e) {
                throw new CrptApiException("Failed to authenticate", e);
            }
        }

        public String getToken() {
            return token != null ? token : "default-token"; // *
        }
    }

    public static class IntroduceGoodsDoc {
        @JsonProperty("document_format")
        private final String documentFormat;
        @JsonProperty("product_group")
        private final String productGroup;
        @JsonProperty("type")
        private final String type;
        @JsonProperty("content")
        private final IntroduceContent content;

        public IntroduceGoodsDoc(String documentFormat, String productGroup, String type, IntroduceContent content) {
            this.documentFormat = Objects.requireNonNull(documentFormat);
            this.productGroup = Objects.requireNonNull(productGroup);
            this.type = Objects.requireNonNull(type);
            this.content = Objects.requireNonNull(content);
            if (!List.of("LP_INTRODUCE_GOODS", "LP_INTRODUCE_GOODS_CSV", "LP_INTRODUCE_GOODS_XML").contains(type)) {
                throw new IllegalArgumentException("Invalid type: must be LP_INTRODUCE_GOODS, LP_INTRODUCE_GOODS_CSV, or LP_INTRODUCE_GOODS_XML");
            }
        }

        public String getDocumentFormat() { return documentFormat; }
        public String getProductGroup() { return productGroup; }
        public String getType() { return type; }
        public IntroduceContent getContent() { return content; }

        public void validate() {
            if (content == null || content.getParticipantInn() == null || content.getOwnerInn() == null ||
                    content.getProducerInn() == null || content.getProductionDate() == null ||
                    content.getProductionType() == null || content.getProducts() == null || content.getProducts().isEmpty()) {
                throw new IllegalArgumentException("Mandatory fields missing in content");
            }
        }
    }

    public static class IntroduceContent {
        @JsonProperty("description")
        private final Description description;
        @JsonProperty("doc_id")
        private final String docId;
        @JsonProperty("doc_status")
        private final String docStatus;
        @JsonProperty("doc_type")
        private final String docType;
        @JsonProperty("importRequest")
        private final Boolean importRequest;
        @JsonProperty("owner_inn")
        private final String ownerInn;
        @JsonProperty("participant_inn")
        private final String participantInn;
        @JsonProperty("producer_inn")
        private final String producerInn;
        @JsonProperty("production_date")
        private final String productionDate;
        @JsonProperty("production_type")
        private final String productionType;
        @JsonProperty("products")
        private final List<ProductItem> products;
        @JsonProperty("reg_date")
        private final String regDate;
        @JsonProperty("reg_number")
        private final String regNumber;

        public IntroduceContent(String participantInn, String docId, String docStatus, String docType, Boolean importRequest,
                                String ownerInn, String producerInn, String productionDate, String productionType,
                                String ownProduction, List<ProductItem> products, String regDate, String regNumber) {
            this.description = new Description(participantInn);
            this.docId = docId;
            this.docStatus = docStatus;
            this.docType = docType;
            this.importRequest = importRequest;
            this.ownerInn = Objects.requireNonNull(ownerInn);
            this.participantInn = Objects.requireNonNull(participantInn);
            this.producerInn = Objects.requireNonNull(producerInn);
            this.productionDate = Objects.requireNonNull(productionDate);
            this.productionType = Objects.requireNonNull(productionType);
            this.products = Objects.requireNonNull(products);
            this.regDate = regDate;
            this.regNumber = regNumber;
        }

        public String getParticipantInn() { return description.getParticipantInn(); }
        public String getDocId() { return docId; }
        public String getDocStatus() { return docStatus; }
        public String getDocType() { return docType; }
        public Boolean getImportRequest() { return importRequest; }
        public String getOwnerInn() { return ownerInn; }
        public String getProducerInn() { return producerInn; }
        public String getProductionDate() { return productionDate; }
        public String getProductionType() { return productionType; }
        public List<ProductItem> getProducts() { return products; }
        public String getRegDate() { return regDate; }
        public String getRegNumber() { return regNumber; }
    }

    public static class Description {
        @JsonProperty("participantInn")
        private final String participantInn;

        public Description(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getParticipantInn() { return participantInn; }
    }

    public static class ProductItem {
        @JsonProperty("certificate_document")
        private final String certificateDocument;
        @JsonProperty("certificate_document_date")
        private final String certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        private final String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        private final String ownerInn;
        @JsonProperty("producer_inn")
        private final String producerInn;
        @JsonProperty("production_date")
        private final String productionDate;
        @JsonProperty("tnved_code")
        private final String tnvedCode;
        @JsonProperty("uit_code")
        private final String uitCode;
        @JsonProperty("uitu_code")
        private final String uituCode;

        public ProductItem(String certificateDocument, String certificateDocumentDate, String certificateDocumentNumber,
                           String ownerInn, String producerInn, String productionDate, String tnvedCode,
                           String uitCode, String uituCode) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.ownerInn = Objects.requireNonNull(ownerInn);
            this.producerInn = Objects.requireNonNull(producerInn);
            this.productionDate = Objects.requireNonNull(productionDate);
            this.tnvedCode = Objects.requireNonNull(tnvedCode);
            this.uitCode = uitCode;
            this.uituCode = uituCode;
            if (uitCode == null && uituCode == null) throw new IllegalArgumentException("UIT or UITU required");
        }
    }

    public static class CrptApiException extends RuntimeException {
        public CrptApiException(String message) { super(message); }
        public CrptApiException(String message, Throwable cause) { super(message, cause); }
    }
}