package com.sistemaspalacios.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;

@EnabledIf("isDockerAvailable")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CorporateBillingServiceApplicationTests {

	static boolean isDockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	@Test
	void contextLoads() {
	}

}
