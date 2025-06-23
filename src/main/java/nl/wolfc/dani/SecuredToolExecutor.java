package nl.wolfc.dani;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;
import java.util.function.Supplier;

public class SecuredToolExecutor extends DefaultToolExecutor {
    private final Supplier<Principal> currentPrincipal;
    private final PrincipalsAllowed allowed;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface PrincipalsAllowed {
        String[] value() default "";
    }

    public SecuredToolExecutor(final Supplier<Principal> currentPrincipal, final Object object, final Method method) {
        super(object, method);
        this.currentPrincipal = currentPrincipal;
        this.allowed = method.getAnnotation(PrincipalsAllowed.class);
    }

    @Override
    public String execute(final ToolExecutionRequest toolExecutionRequest, final Object memoryId) {
        if (allowed != null) {
            final Principal principal = currentPrincipal.get();
            if (principal == null)
                throw new IllegalCallerException("No caller identified");
            final String currentName = currentPrincipal.get().getName();
            System.err.println("*** " + currentName);
            if (!Arrays.stream(allowed.value()).anyMatch(currentName::equals))
                throw new IllegalCallerException(currentName + " is not allowed");
        }
        return super.execute(toolExecutionRequest, memoryId);
    }
}
