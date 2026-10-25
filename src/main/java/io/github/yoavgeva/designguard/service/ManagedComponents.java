package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;

/**
 * Classes a container creates and manages, which are components rather than values: Spring beans, including those of
 * stereotypes such as {@code @RestController}, CDI and {@code @Singleton} beans, Micronaut controllers, JAX-RS
 * resources and providers, AspectJ aspects, WebSocket endpoints, and servlets and filters. What state a component
 * holds is a question of whether it is shared, not of whether a value changes.
 */
final class ManagedComponents {

    private static final List<String> ANNOTATIONS = List.of("org.springframework.stereotype.Component",
            "jakarta.inject.Singleton", "javax.inject.Singleton", "jakarta.enterprise.context.ApplicationScoped",
            "javax.enterprise.context.ApplicationScoped", "jakarta.enterprise.context.RequestScoped",
            "javax.enterprise.context.RequestScoped", "jakarta.enterprise.context.SessionScoped",
            "javax.enterprise.context.SessionScoped", "jakarta.enterprise.context.Dependent",
            "javax.enterprise.context.Dependent", "io.micronaut.http.annotation.Controller",
            "io.micronaut.http.annotation.Filter", "jakarta.ws.rs.Path", "javax.ws.rs.Path", "jakarta.ws.rs.ext.Provider",
            "javax.ws.rs.ext.Provider", "org.aspectj.lang.annotation.Aspect", "jakarta.websocket.server.ServerEndpoint",
            "javax.websocket.server.ServerEndpoint", "jakarta.servlet.annotation.WebServlet",
            "javax.servlet.annotation.WebServlet", "jakarta.servlet.annotation.WebFilter",
            "javax.servlet.annotation.WebFilter");
    private static final List<String> TYPES = List.of("jakarta.servlet.Servlet", "javax.servlet.Servlet",
            "jakarta.servlet.Filter", "javax.servlet.Filter", "org.springframework.web.server.WebFilter");

    private ManagedComponents() {
    }

    /** Whether a container creates and manages the class, by an annotation on it or one it is meta-annotated with. */
    static boolean contains(JavaClass type) {
        return ANNOTATIONS.stream().anyMatch(type::isMetaAnnotatedWith) || TYPES.stream().anyMatch(type::isAssignableTo);
    }
}
