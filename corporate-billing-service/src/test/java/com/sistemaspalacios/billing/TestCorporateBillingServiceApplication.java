package com.sistemaspalacios.billing;

import org.springframework.boot.SpringApplication;

public class TestCorporateBillingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CorporateBillingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
