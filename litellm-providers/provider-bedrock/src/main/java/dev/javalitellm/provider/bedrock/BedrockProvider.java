package dev.javalitellm.provider.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.http.StatusErrorMapper;
import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.core.spi.LlmProvider;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

/**
 * AWS Bedrock adapter using the Converse API. The AWS SDK handles SigV4 signing, the credential
 * chain and event-stream framing.
 *
 * <p>Configuration: {@link ProviderConfig#region()} selects the AWS region (default us-east-1).
 * {@link ProviderConfig#apiKey()} accepts {@code "accessKeyId:secretAccessKey[:sessionToken]"};
 * when absent the standard AWS credential chain applies (env vars, profile, instance role).
 */
public final class BedrockProvider implements LlmProvider {

    private static final String DEFAULT_REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BedrockTransformer transformer = new BedrockTransformer(mapper);
    private final Map<String, BedrockRuntimeClient> syncClients = new ConcurrentHashMap<>();
    private final Map<String, BedrockRuntimeAsyncClient> asyncClients = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "bedrock";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.STREAMING, Capability.TOOLS, Capability.VISION);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ProviderConfig config) {
        try {
            var response = syncClient(config).converse(transformer.toConverseRequest(request, request.model()));
            return transformer.fromConverseResponse(response, request.model());
        } catch (AwsServiceException e) {
            throw mapAwsError(e, request.model());
        } catch (SdkClientException e) {
            throw new LiteLlmException("bedrock client error: " + e.getMessage(), name(), request.model(), 0, true, e);
        }
    }

    @Override
    public void chatStream(ChatRequest request, ProviderConfig config, StreamHandler handler) {
        BedrockStreamParser parser = new BedrockStreamParser(request.model(), mapper);
        ConverseStreamResponseHandler responseHandler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        .onContentBlockStart(event -> emit(handler, parser.onContentBlockStart(event)))
                        .onContentBlockDelta(event -> emit(handler, parser.onContentBlockDelta(event)))
                        .onMessageStop(event -> emit(handler, parser.onMessageStop(event)))
                        .onMetadata(event -> emit(handler, parser.onMetadata(event)))
                        .build())
                .build();
        try {
            asyncClient(config)
                    .converseStream(transformer.toConverseStreamRequest(request, request.model()), responseHandler)
                    .join();
            handler.onComplete();
        } catch (CompletionException e) {
            handler.onError(unwrap(e, request.model()));
        } catch (AwsServiceException e) {
            handler.onError(mapAwsError(e, request.model()));
        } catch (SdkClientException e) {
            handler.onError(new LiteLlmException(
                    "bedrock client error: " + e.getMessage(), name(), request.model(), 0, true, e));
        }
    }

    private static void emit(StreamHandler handler, ChatChunk chunk) {
        if (chunk != null) {
            handler.onChunk(chunk);
        }
    }

    private LiteLlmException unwrap(CompletionException e, String model) {
        if (e.getCause() instanceof AwsServiceException aws) {
            return mapAwsError(aws, model);
        }
        return new LiteLlmException("bedrock stream failed: " + e.getMessage(), name(), model, 0, true, e);
    }

    private LiteLlmException mapAwsError(AwsServiceException e, String model) {
        String message = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
        return StatusErrorMapper.map(e.statusCode(), message, name(), model);
    }

    private BedrockRuntimeClient syncClient(ProviderConfig config) {
        return syncClients.computeIfAbsent(clientKey(config), key -> {
            var builder = BedrockRuntimeClient.builder()
                    .region(Region.of(config.region() != null ? config.region() : DEFAULT_REGION))
                    .credentialsProvider(credentials(config));
            if (config.apiBase() != null) {
                builder.endpointOverride(URI.create(config.apiBase()));
            }
            return builder.build();
        });
    }

    private BedrockRuntimeAsyncClient asyncClient(ProviderConfig config) {
        return asyncClients.computeIfAbsent(clientKey(config), key -> {
            var builder = BedrockRuntimeAsyncClient.builder()
                    .region(Region.of(config.region() != null ? config.region() : DEFAULT_REGION))
                    .credentialsProvider(credentials(config));
            if (config.apiBase() != null) {
                builder.endpointOverride(URI.create(config.apiBase()));
            }
            return builder.build();
        });
    }

    private static String clientKey(ProviderConfig config) {
        return config.region() + "|" + config.apiBase() + "|" + (config.apiKey() != null ? config.apiKey() : "chain");
    }

    private static software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials(ProviderConfig config) {
        if (config.apiKey() == null) {
            return DefaultCredentialsProvider.builder().build();
        }
        String[] parts = config.apiKey().split(":", 3);
        if (parts.length < 2) {
            throw new dev.javalitellm.core.exception.AuthenticationException(
                    "bedrock apiKey must be 'accessKeyId:secretAccessKey[:sessionToken]'", "bedrock", null);
        }
        return StaticCredentialsProvider.create(
                parts.length == 3
                        ? AwsSessionCredentials.create(parts[0], parts[1], parts[2])
                        : AwsBasicCredentials.create(parts[0], parts[1]));
    }
}
