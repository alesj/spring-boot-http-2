package me.snowdrop.protocol;

import io.undertow.UndertowOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainerFactory;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Http2Application extends SpringBootServletInitializer {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(Http2Application.class, args);
    }

    @Bean
    public EmbeddedServletContainerCustomizer undertowCustomizer() {
        return (container) -> {
            if (container instanceof UndertowEmbeddedServletContainerFactory) {
                UndertowEmbeddedServletContainerFactory factory = (UndertowEmbeddedServletContainerFactory) container;
                factory.addBuilderCustomizers(
                    builder -> builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                );
            }
        };
    }


    @RequestMapping("/")
    String hello() {
        return "hello!";
    }
}
