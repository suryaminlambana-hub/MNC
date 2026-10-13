package com.mnc.ausy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AusyApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AusyApplication.class);
        // Mode tugas (REQ_AUSY_00058) tidak boleh menyalakan Tomcat: aplikasi webnya sudah
        // memegang port 8080, dan proses kedua yang mencoba mengikatnya akan mati sebelum
        // tugasnya sempat dikerjakan. Diatur di sini, bukan diserahkan ke baris crontab —
        // satu argumen yang terlupa akan menggagalkan tugasnya tiap malam tanpa jelas sebabnya.
        for (String a : args)
            if (a.startsWith("--ausy.task=")) {
                app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                break;
            }
        app.run(args);
    }
}
