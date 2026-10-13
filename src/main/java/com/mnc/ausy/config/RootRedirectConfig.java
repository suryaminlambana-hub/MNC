package com.mnc.ausy.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;

/**
 * Mengalihkan {@code http://localhost:8080/} ke aplikasi yang duduk di {@code /ausy}.
 *
 * Aplikasi dipasang di context path {@code /ausy}, jadi alamat tanpa akhiran itu tidak pernah
 * sampai ke Spring sama sekali — Tomcat menjawabnya sendiri dengan halaman "HTTP Status 404"
 * berwarna abu-abu. Halaman itu terbaca seperti aplikasinya mati, padahal hanya alamatnya yang
 * kurang satu ruas, dan orang menghabiskan waktu memeriksa hal yang tidak rusak.
 *
 * Karena 404 itu terjadi DI LUAR context aplikasi, penanganan error Spring tidak bisa
 * menangkapnya. Satu-satunya cara adalah mendaftarkan context ROOT tersendiri yang isinya hanya
 * satu servlet pengalih.
 *
 * Ruas alamat dan query dibawa serta, sehingga tautan yang dibagikan tanpa {@code /ausy}
 * (misalnya {@code localhost:8080/admin/acctxt}) tetap mendarat di tempat yang benar.
 */
// Hanya berlaku saat aplikasi berjalan sebagai web. Mode tugas (--ausy.task=..., REQ_AUSY_00058)
// berjalan tanpa Tomcat, sehingga bean khas web seperti HttpSecurity tidak ada dan konfigurasi
// ini akan menggagalkan start kalau tetap dimuat.
@ConditionalOnWebApplication
@Configuration
public class RootRedirectConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RootRedirectConfig.class);

    @Bean
    public TomcatServletWebServerFactory servletContainer(Environment env) {
        String contextPath = env.getProperty("server.servlet.context-path", "");

        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                // Tanpa context path, ROOT sudah ditempati aplikasi — tidak ada yang perlu dialihkan.
                if (!contextPath.isBlank() && !"/".equals(contextPath)) {
                    try {
                        addRootRedirect(tomcat, contextPath);
                    } catch (Exception e) {
                        // Kenyamanan saja: kalau gagal, biarkan Tomcat menjawab 404 seperti dulu.
                        // Jangan sampai menggagalkan start aplikasi hanya karena pengalihan ini.
                        LOG.warn("Pengalihan / -> {} tidak bisa dipasang: {}", contextPath, e.getMessage());
                    }
                }
                return super.getTomcatWebServer(tomcat);
            }
        };
    }

    private void addRootRedirect(Tomcat tomcat, String contextPath) {
        Context root = tomcat.addContext("", null);
        Tomcat.addServlet(root, "rootRedirect", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String path = req.getRequestURI();
                String query = req.getQueryString();
                resp.sendRedirect(contextPath + (path == null || path.equals("/") ? "/" : path)
                        + (query == null ? "" : "?" + query));
            }
        });
        root.addServletMappingDecoded("/*", "rootRedirect");
        LOG.info("Alamat tanpa {} dialihkan ke sana (buka http://localhost:<port>/ juga bisa)", contextPath);
    }
}
