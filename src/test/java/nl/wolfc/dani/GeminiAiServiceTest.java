package nl.wolfc.dani;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.security.Principal;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.TimeZone;
import java.util.function.BiFunction;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom;
import static dev.langchain4j.exception.IllegalConfigurationException.illegalConfiguration;

public class GeminiAiServiceTest {
    private static final Logger LOGGER = Logger.getLogger(GeminiAiServiceTest.class.getName());

    static class SecurityContext {
        static final ThreadLocal<Queue<Principal>> currentPrincipal = ThreadLocal.withInitial(() -> new LinkedList<>());
    }

    static class AccountChecker {
        @SecuredToolExecutor.PrincipalsAllowed("Bob")
        @Tool("Returns the current balance for the given account number")
        long getBalanceOf(@P("account number") final String accountNumber) {
            final long balance = new Random().nextLong(1000000L);
            LOGGER.info("** Balance for account number " + accountNumber + " is " + balance);
            return balance;
        }
    }

    static class TimeChecker {
        @Tool("Returns the current time for a given timezone")
        String getCurrentTimeOf(@P("time zone") final String timeZone) {
            LOGGER.info("** current time of " + timeZone);
            final DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL);
            fmt.setTimeZone(TimeZone.getTimeZone(timeZone));
            return fmt.format(new Date());
        }
    }

    static class RuntimeContext {
        @Tool("Return the users identity")
        String getCallerPrincipal() {
            final Principal p = SecurityContext.currentPrincipal.get().peek();
            return p == null ? null : p.getName();
        }
    }

    interface Assistant {
        //@SystemMessage("test")
        String chat(String userMessage);
    }

    public static void main(final String[] args) throws Exception {
        final Logger logger = Logger.getLogger(ToolsExecutingChatModel.class.getName());
        logger.setLevel(Level.FINE);
        final ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        logger.addHandler(handler);

        // VertexAiGeminiChatModel doesn't do SystemMessages
        final GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .modelName("gemini-2.5-flash");
        final ToolsExecutingChatModel executingChatModel = new ToolsExecutingChatModel(builder.build(), builder.build());
        final Object objectWithTool = new RuntimeContext();
        final Method method = objectWithTool.getClass().getDeclaredMethod("getCallerPrincipal");
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(executingChatModel)
                .tools(toolsMap((o, m) -> new SecuredToolExecutor(() -> SecurityContext.currentPrincipal.get().peek(), o, m), new AccountChecker()))
                .tools(new TimeChecker())
                .tools(Map.of(ToolSpecifications.toolSpecificationFrom(method), new DefaultToolExecutor(objectWithTool, method)))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        //String question = "What is the current balance of account number 8954325?";
        //String question = "What is your name?";
        //String question = "What is the current time in Athens?";
        //String question = "What are you?";
        //String question = "What is the capital of Canada?";
        SecurityContext.currentPrincipal.get().add(() -> "Bob");
        //String question = "Who am I?";
        String question = "What is the balance of account #1234?";

        System.out.println("> " + question);

        String answer = assistant.chat(question);

        System.out.println("< " + answer);
    }

    private static Map<ToolSpecification, ToolExecutor> toolsMap(final BiFunction<Object, Method, ToolExecutor> toolExecutorBuilder, Object... objectsWithTools) {
        final Map<ToolSpecification, ToolExecutor> map = new HashMap<>();
        for (Object objectWithTool : objectsWithTools) {
            if (objectWithTool instanceof Class) {
                throw illegalConfiguration("Tool '%s' must be an object, not a class", objectWithTool);
            }

            for (Method method : objectWithTool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    final ToolSpecification toolSpecification = toolSpecificationFrom(method);
                    map.put(toolSpecification, toolExecutorBuilder.apply(objectWithTool, method));
                }
            }
        }
        return map;
    }
}
