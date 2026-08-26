package dev.memos.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "dev.memos")
@EnableScheduling
public class MemosWorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(MemosWorkerApplication.class, args);
  }
}
