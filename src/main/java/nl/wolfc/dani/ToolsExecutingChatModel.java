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
            final String systemMessageText = "You are a tool evaluator which MUST always respond with a JSON array of applicable tools adhering to the following schema:" +
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
                    "          \"description\": \"The stringified map with arguments to pass to the tool\"\n" +
                    "        }\n" +
                    "      },\n" +
                    "      \"required\": [\n" +
                    "        \"name\",\n" +
                    "        \"arguments\"\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}\n" +
                    "\nYou may only choose from the following tools:\n" +
                    gson.toJson(toolSpecifications) + "\n" +
                    "If no tools are applicable respond only with an empty JSON array. NEVER respond with any other form of answer or explanation, just respond with an empty JSON array.";
            final List<ChatMessage> assistantMessages = new ArrayList<>();
            assistantMessages.add(SystemMessage.from(systemMessageText));
            for (ChatMessage msg : messages) {
                if (msg.type() != ChatMessageType.SYSTEM) {
                    assistantMessages.add(msg);
                }
            }
            Response<AiMessage> response;
            String responseText;
            RuntimeException exception = null;
            for (int retries = 0; retries < 3; retries++) {
                LOGGER.fine("assistantMessage = " + assistantMessages);
                response = toolsAssistant.generate(assistantMessages);
                // sometimes you get strings quoted with ` instead of ".
                responseText = response.content().text().replace("`", "'");
                LOGGER.fine("responseText = " + responseText);
                try {
                    // TODO: sometimes the one parameter is given as a string as opposed to a stringified map, this
                    // results in an error from ToolExecutionRequestUtil::argumentsAsMap
                    // So it might be prudent to do some more sanitizing.
                    final List<ToolExecutionRequest> executionRequests = gson.fromJson(responseText, listType);
                    if (executionRequests != null && executionRequests.size() > 0) {
                        return new Response<>(AiMessage.aiMessage(executionRequests), response.tokenUsage(), response.finishReason());
                    }
                    exception = null;
                    break;
                } catch (RuntimeException e) {
                    LOGGER.warning("Error: " + e.getMessage());
                    assistantMessages.add(response.content());
                    assistantMessages.add(UserMessage.from("Error: " + e.getMessage() + "\nTry again. If there are no applicable tools respond only with an empty JSON array."));
                    exception = e;
                }
            }
            if (exception != null) throw exception;
        }
        {
            // this is just a hacky result generator because currently the lab AI engine fails with:
            // Exception: can only concatenate str (not "NoneType") to str
            final List<ChatMessage> newMessages = new ArrayList<>();
            final List<ChatMessage> executionResultMessages = messages.stream().filter(ToolExecutionResultMessage.class::isInstance).collect(Collectors.toList());
            if (executionResultMessages.size() > 0) {
                final String systemMessageText = "Given the following tools:\n" +
                        gson.toJson(toolSpecifications) +
                        "\n\n" +
                        "And the following tool execution requests:\n" +
                        gson.toJson(messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).map(m -> m.toolExecutionRequests()).flatMap(Collection::stream).collect(Collectors.toList())) +
                        "\n\n" +
                        "And the following tool execution results:\n" +
                        gson.toJson(executionResultMessages);
                newMessages.add(SystemMessage.from(systemMessageText));
            }
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
