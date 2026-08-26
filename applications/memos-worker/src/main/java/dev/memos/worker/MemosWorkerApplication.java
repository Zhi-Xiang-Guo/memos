package dev.memos.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.memos")
public class MemosWorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(MemosWorkerApplication.class, args);
  }
}
