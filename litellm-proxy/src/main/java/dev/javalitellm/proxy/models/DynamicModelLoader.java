package dev.javalitellm.proxy.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.proxy.config.ProxyConfigLoader;
import dev.javalitellm.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads persisted dynamic models into the live router once, after the DB schema is migrated (an
 * {@link ApplicationRunner} runs after Flyway and full context startup).
 */
@Component
public class DynamicModelLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelLoader.class);

    private final Router router;
    private final ModelDeploymentStore store;
    private final ProxyConfigLoader configLoader;
    private final ObjectMapper mapper;

    public DynamicModelLoader(
            Router router, ModelDeploymentStore store, ProxyConfigLoader configLoader, ObjectMapper mapper) {
        this.router = router;
        this.store = store;
        this.configLoader = configLoader;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (ModelDeploymentStore.StoredModel model : store.all()) {
            try {
                router.addDeployment(configLoader.parseEntry(
                        model.modelName(), mapper.readTree(model.litellmParamsJson()), model.id()));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("skipping malformed stored model {}", model.id(), e);
            }
        }
    }
}
