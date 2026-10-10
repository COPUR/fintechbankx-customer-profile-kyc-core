package com.bank.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-cus-profile-kyc: the Customer bounded context extracted from
 * enterprise-loan-management-system. Owns customer profiles and their credit
 * position; other services reserve and release credit over HTTP and learn
 * about changes from events on the aggregate topic evt.cus.customer.v1.
 *
 * <p>With the first argument {@code migrate} the image runs as the chart's
 * migration Job instead ({@link DatabaseMigration}) and exits.
 */
@SpringBootApplication
public class CustomerProfileKycApplication {

    public static void main(String[] args) {
        if (DatabaseMigration.isRequested(args)) {
            System.exit(DatabaseMigration.run(DatabaseMigration.arguments(args)));
        }
        SpringApplication.run(CustomerProfileKycApplication.class, args);
    }
}
