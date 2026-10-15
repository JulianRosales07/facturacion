package com.sistemaspalacios.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CorporateBillingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CorporateBillingServiceApplication.class, args);
	}

}
