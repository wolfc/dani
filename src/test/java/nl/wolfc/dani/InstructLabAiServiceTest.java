package nl.wolfc.dani;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.text.DateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InstructLabAiServiceTest {
    private static final Logger LOGGER = Logger.getLogger(InstructLabAiServiceTest.class.getName());

    static class AccountChecker {
        @Tool("Returns the current balance for the given account number")
        long getBalanceOf(@P("account number") final String accountNumber) {
            final long balance = new Random().nextLong(1000000L);
            LOGGER.info("** Balance for account number " + accountNumber + " is " + balance);
            return balance;
        }
        @Tool("Returns the current time for a given timezone")
        String getCurrentTimeOf(@P("time zone") final String timeZone) {
            LOGGER.info("** current time of " + timeZone);
            final DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL);
            fmt.setTimeZone(TimeZone.getTimeZone(timeZone));
            return fmt.format(new Date());
        }
    }

    interface Assistant {
        //@SystemMessage("test")
        String chat(String userMessage);
    }

    public static void main(final String[] args) {
        final Logger logger = Logger.getLogger(ToolsExecutingChatModel.class.getName());
        logger.setLevel(Level.FINE);
        final ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        logger.addHandler(handler);

        final OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey("not needed")
                .baseUrl("http://localhost:8000/v1")
                .timeout(Duration.ofSeconds(120));
        final ToolsExecutingChatModel executingChatModel = new ToolsExecutingChatModel(builder.build(), builder.build());
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(executingChatModel)
                .tools(new AccountChecker())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        //String question = "What is the current balance of account number 8954325?";
        //String question = "What is your name?";
        String question = "What is the current time in Athens?";
        //String question = "What are you?";
        //String question = "What is the capital of Canada?";

        System.out.println("> " + question);

        String answer = assistant.chat(question);

        System.out.println("< " + answer);
    }
}
