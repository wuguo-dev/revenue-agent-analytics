package com.salesanalytics;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.salesanalytics.**.mapper")
public class SalesAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(SalesAnalysisApplication.class, args);
    }
}
