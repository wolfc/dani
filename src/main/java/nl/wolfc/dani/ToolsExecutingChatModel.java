package nl.wolfc.dani;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ToolsExecutingChatModel implements ChatLanguageModel {
    private static final Logger LOGGER = Logger.getLogger(ToolsExecutingChatModel.class.getName());

    private final ChatLanguageModel toolsAssistant;
    private final ChatLanguageModel delegate;
    //private final Gson gson = new Gson();
    /*
     * setLenient because of "improved" responses:
     * responseText = [
     *  {"id":"12345678","name":"getBalanceOf","arguments":"{\"arg0\":\"8954325\"}"}
     * ]
     *
     * In this improved response, the tool's name, arguments, and a unique id are provided. This makes it easier to identify and use the appropriate tool in the provided set of tools.
     */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().setLenient().create();

    private final static Type listType = new TypeToken<ArrayList<ToolExecutionRequest>>(){}.getType();

    public ToolsExecutingChatModel(final ChatLanguageModel toolsAssistant, final ChatLanguageModel delegate) {
        this.toolsAssistant = toolsAssistant;
        this.delegate = delegate;
    }

    @Override
    public Response<AiMessage> generate(final List<ChatMessage> messages) {
        return delegate.generate(messages);
    }

    @Override
    public Response<AiMessage> generate(final List<ChatMessage> messages, final List<ToolSpecification> toolSpecifications) {
        LOGGER.fine("messages = " + messages);
        if (toolSpecifications != null && toolSpecifications.size() > 0 && noToolExecutionResultsIn(messages)) {
            // Inspired by https://github.com/instruct-lab/taxonomy/pull/221 and with the help of its author Hemslo Wang
            final String systemMessageText = "Given the following tools:\n" +
                    "\n" +
                    gson.toJson(toolSpecifications) +
                    "\n" +
                    "Select all suitable tools for the given input, respond with a valid JSON array. If no tool is suitable, respond with an empty JSON array. Do not give any other form of answer or explanation.\n\n" +
                    "With the response adhering to the following JSON schema:\n" +
                    "\n" +
                    "{\n" +
                    "  \"type\": \"array\",\n" +
                    "  \"items\": [\n" +
                    "    {\n" +
                    "      \"type\": \"object\",\n" +
                    "      \"properties\": {\n" +
                    "        \"id\": {\n" +
                    "          \"type\": \"string\",\n" +
                    "          \"description\": \"An unique id\"\n" +
                    "        },\n" +
                    "        \"name\": {\n" +
                    "          \"type\": \"string\",\n" +
                    "          \"description\": \"The name of the tool to use\"\n" +
                    "        },\n" +
                    "        \"arguments\": {\n" +
                    "          \"type\": \"string\"\n" +
                    "          \"description\": \"The arguments to pass to the tool as stringified JSON object\"\n" +
                    "        }\n" +
                    "      },\n" +
                    "      \"required\": [\n" +
                    "        \"name\",\n" +
                    "        \"arguments\"\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";
            final List<ChatMessage> assistantMessages = new ArrayList<>();
            assistantMessages.add(SystemMessage.from(systemMessageText));
            for (ChatMessage msg : messages) {
                if (msg.type() != ChatMessageType.SYSTEM) {
                    assistantMessages.add(msg);
                }
            }
            LOGGER.fine("assistantMessage = " + assistantMessages);
            final Response<AiMessage> response = toolsAssistant.generate(assistantMessages);
            // sometimes you get strings quoted with ` instead of ".
            final String responseText = response.content().text().replace("`", "'");
            LOGGER.fine("responseText = " + responseText);
            final List<ToolExecutionRequest> executionRequests = gson.fromJson(responseText, listType);
            if (executionRequests != null && executionRequests.size() > 0) {
                return new Response<>(AiMessage.aiMessage(executionRequests), response.tokenUsage(), response.finishReason());
            }
        }
        {
            // this is just a hacky result generator because currently the lab AI engine fails with:
            // Exception: can only concatenate str (not "NoneType") to str
            final String systemMessageText = "Given the following tools:\n" +
                    gson.toJson(toolSpecifications) +
                    "\n\n" +
                    "And the following tool execution requests:\n" +
                    gson.toJson(messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).map(m -> m.toolExecutionRequests()).flatMap(Collection::stream).collect(Collectors.toList())) +
                    "\n\n" +
                    "And the following tool execution results:\n" +
                    gson.toJson(messages.stream().filter(ToolExecutionResultMessage.class::isInstance).collect(Collectors.toList()));
            final List<ChatMessage> newMessages = new ArrayList<>();
            newMessages.add(SystemMessage.from(systemMessageText));
            newMessages.addAll(messages.stream().filter(UserMessage.class::isInstance).collect(Collectors.toList()));
            LOGGER.fine("newMessages = " + newMessages);
            return delegate.generate(newMessages);
        }
        //return delegate.generate(messages, toolSpecifications);
    }

    @Override
    public Response<AiMessage> generate(final List<ChatMessage> messages, final ToolSpecification toolSpecification) {
        return generate(messages, Arrays.asList(toolSpecification));
    }

    private static boolean noToolExecutionResultsIn(final List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage)
                return false;
        }
        return true;
    }
}
