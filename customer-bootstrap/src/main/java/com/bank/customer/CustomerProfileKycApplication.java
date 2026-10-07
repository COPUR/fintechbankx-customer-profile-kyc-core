package com.bank.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-cus-profile-kyc: the Customer bounded context extracted from
 * enterprise-loan-management-system. Owns customer profiles and their credit
 * position; other services reserve and release credit over HTTP and learn
 * about changes from evt.cus.customer.* events.
 */
@SpringBootApplication
public class CustomerProfileKycApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerProfileKycApplication.class, args);
    }
}
