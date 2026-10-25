package io.github.yoavgeva.designguard.service;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.stream.Stream;

/**
 * Classes a container creates and manages, which are components rather than values: Spring beans, including those of
 * stereotypes such as {@code @RestController}, CDI and {@code @Singleton} beans, Micronaut controllers, JAX-RS
 * resources and providers, AspectJ aspects, WebSocket endpoints, servlets and filters, EJBs and command-line
 * commands. What state a component holds is a question of whether it is shared, not of whether a value changes. Some
 * are created for each use, and hold the state of that use: a command for each run, a WebSocket endpoint for each
 * connection, a request-, session-, view-, flow- or conversation-scoped bean, a stateful EJB, and a batch job's
 * readers, writers and listeners for each step.
 */
final class ManagedComponents {

    private static final List<String> ANNOTATIONS = List.of("org.springframework.stereotype.Component",
            "jakarta.inject.Singleton", "javax.inject.Singleton", "jakarta.enterprise.context.ApplicationScoped",
            "javax.enterprise.context.ApplicationScoped", "jakarta.enterprise.context.Dependent",
            "javax.enterprise.context.Dependent", "io.micronaut.http.annotation.Controller",
            "io.micronaut.http.annotation.Filter", "jakarta.ws.rs.Path", "javax.ws.rs.Path", "jakarta.ws.rs.ext.Provider",
            "javax.ws.rs.ext.Provider", "org.aspectj.lang.annotation.Aspect", "jakarta.servlet.annotation.WebServlet",
            "javax.servlet.annotation.WebServlet", "jakarta.servlet.annotation.WebFilter",
            "javax.servlet.annotation.WebFilter", "jakarta.ejb.Stateless", "javax.ejb.Stateless", "jakarta.ejb.Singleton",
            "javax.ejb.Singleton", "jakarta.ejb.MessageDriven", "javax.ejb.MessageDriven");
    /** Components a framework creates for each use: commands, endpoints for a connection, request-scoped beans. */
    private static final List<String> PER_USE = List.of("org.aesh.command.CommandDefinition",
            "org.aesh.command.GroupCommandDefinition", "picocli.CommandLine$Command",
            "jakarta.websocket.server.ServerEndpoint", "javax.websocket.server.ServerEndpoint",
            "jakarta.enterprise.context.RequestScoped", "javax.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped", "javax.enterprise.context.SessionScoped",
            "org.springframework.web.context.annotation.RequestScope",
            "org.springframework.web.context.annotation.SessionScope", "jakarta.faces.view.ViewScoped",
            "javax.faces.view.ViewScoped", "jakarta.faces.flow.FlowScoped", "javax.faces.flow.FlowScoped",
            "jakarta.enterprise.context.ConversationScoped", "javax.enterprise.context.ConversationScoped",
            "jakarta.ejb.Stateful", "javax.ejb.Stateful");
    /** Packages of Jakarta Batch's artifacts, which a job creates for each step it runs. */
    private static final List<String> BATCH_ARTIFACTS = List.of("jakarta.batch.api", "javax.batch.api");
    private static final List<String> TYPES = List.of("jakarta.servlet.Servlet", "javax.servlet.Servlet",
            "jakarta.servlet.Filter", "javax.servlet.Filter", "org.springframework.web.server.WebFilter");

    private ManagedComponents() {
    }

    /** Whether a container creates and manages the class, by an annotation on it or one it is meta-annotated with. */
    static boolean contains(JavaClass type) {
        return ANNOTATIONS.stream().anyMatch(type::isMetaAnnotatedWith) || TYPES.stream().anyMatch(type::isAssignableTo)
                || isCreatedPerUse(type);
    }

    /** Whether a framework creates the class for each use, as a command for each run, so no two uses share it. */
    static boolean isCreatedPerUse(JavaClass type) {
        return PER_USE.stream().anyMatch(type::isMetaAnnotatedWith) || Stream.concat(type.getAllRawInterfaces().stream(),
                        type.getAllRawSuperclasses().stream())
                .anyMatch(supertype -> BATCH_ARTIFACTS.stream().anyMatch(supertype.getPackageName()::startsWith));
    }
}
