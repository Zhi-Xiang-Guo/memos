package dev.memos.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.memos")
public class MemosApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(MemosApiApplication.class, args);
  }
}
