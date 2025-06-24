package nl.wolfc.dani;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;

import javax.annotation.security.RolesAllowed;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;
import java.util.function.Supplier;

public class SecuredToolExecutor extends DefaultToolExecutor {
    private final Supplier<Context> currentContext;
    private final PrincipalsAllowed principalsAllowed;
    private final RolesAllowed rolesAllowed;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface PrincipalsAllowed {
        String[] value() default "";
    }

    public interface Context {
        Principal currentPrincipal();
        String[] currentRoles();
    }

    public SecuredToolExecutor(final Supplier<Context> currentContext, final Object object, final Method method) {
        super(object, method);
        this.currentContext = currentContext;
        this.principalsAllowed = method.getAnnotation(PrincipalsAllowed.class);
        this.rolesAllowed = method.getAnnotation(RolesAllowed.class);
    }

    @Override
    public String execute(final ToolExecutionRequest toolExecutionRequest, final Object memoryId) {
        final Context ctx = currentContext.get();
        if (principalsAllowed != null) {
            if (ctx == null)
                throw new IllegalStateException("No security context set");
            final Principal principal = ctx.currentPrincipal();
            if (principal == null)
                throw new IllegalCallerException("No caller identified");
            final String currentName = principal.getName();
            if (!Arrays.stream(principalsAllowed.value()).anyMatch(currentName::equals))
                throw new IllegalCallerException(currentName + " is not allowed");
        }
        if (rolesAllowed != null) {
            if (ctx == null)
                throw new IllegalStateException("No security context set");
            final String[] roles = ctx.currentRoles();
            if(!Arrays.stream(rolesAllowed.value()).anyMatch(r -> Arrays.stream(roles).anyMatch(r::equals)))
                throw new IllegalCallerException("No matching role");
        }
        return super.execute(toolExecutionRequest, memoryId);
    }
}
