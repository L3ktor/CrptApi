package com.test;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        try (CrptApi api = new CrptApi(TimeUnit.SECONDS, 10)) {
            CrptApi.ProductItem product = new CrptApi.ProductItem(
                    "CONFORMITY_CERTIFICATE", // certificate_document
                    "2025-09-26", // certificate_document_date
                    "CERT123", // certificate_document_number
                    "1234567890", // owner_inn
                    "1234567890", // producer_inn
                    "2025-09-26", // production_date
                    "6401000000", // tnved_code
                    "11111111111111111111111111111111111111", // uit_code
                    null // uitu_code
            );

            CrptApi.IntroduceContent content = new CrptApi.IntroduceContent(
                    "1234567890", // participant_inn
                    "DOC123", // doc_id
                    "DRAFT", // doc_status
                    "LP_INTRODUCE_GOODS", // doc_type
                    false, // importRequest
                    "1234567890", // owner_inn
                    "1234567890", // participant_inn
                    "1234567890", // producer_inn
                    "2025-09-26", // production_date
                    "OWN_PRODUCTION", // production_type
                    List.of(product), // products
                    "2025-09-26T12:00:00", // reg_date
                    "REG123" // reg_number
            );

            CrptApi.IntroduceGoodsDoc doc = new CrptApi.IntroduceGoodsDoc(
                    "JSON", // document_format
                    "shoes", // product_group
                    "LP_INTRODUCE_GOODS", // type
                    content
            );

            String signature = "sample-signature"; // Замените на реальную подпись УКЭП
            String response = api.createIntroduceDoc(doc, signature);
            System.out.println("Response: " + response);
        } catch (CrptApi.CrptApiException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}