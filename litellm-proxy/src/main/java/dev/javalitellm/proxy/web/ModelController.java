package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.proxy.config.ProxyConfigLoader;
import dev.javalitellm.proxy.models.ModelDeploymentStore;
import dev.javalitellm.router.Deployment;
import dev.javalitellm.router.Router;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dynamic model management, master-key only (enforced by AuthFilter via /model/ prefix). Added
 * models take effect immediately on the live router and persist across restarts.
 */
@RestController
public class ModelController {

    private final Router router;
    private final ModelDeploymentStore store;
    private final ProxyConfigLoader configLoader;
    private final ObjectMapper mapper;

    public ModelController(
            Router router, ModelDeploymentStore store, ProxyConfigLoader configLoader, ObjectMapper mapper) {
        this.router = router;
        this.store = store;
        this.configLoader = configLoader;
        this.mapper = mapper;
    }

    @PostMapping(value = "/model/new", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode add(@RequestBody JsonNode body) {
        String modelName = body.path("model_name").asText(null);
        JsonNode params = body.path("litellm_params");
        if (modelName == null || params.isMissingNode()) {
            throw new BadRequestException("'model_name' and 'litellm_params' are required", null, null);
        }
        String id = modelName + "-dyn-" + Long.toHexString(System.nanoTime());
        Deployment deployment = configLoader.parseEntry(modelName, params, id); // validate before persisting
        store.save(id, modelName, params.toString());
        router.addDeployment(deployment);

        ObjectNode response = mapper.createObjectNode();
        response.put("model_name", modelName);
        response.put("id", id);
        response.put("added", true);
        return response;
    }

    @GetMapping(value = "/model/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode info() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (Map.Entry<String, List<Deployment>> group : router.deployments().entrySet()) {
            for (Deployment deployment : group.getValue()) {
                ObjectNode node = data.addObject();
                node.put("model_name", group.getKey());
                node.put("id", deployment.id());
                node.put("model", deployment.model());
                node.put("weight", deployment.weight());
            }
        }
        return root;
    }

    @PostMapping(value = "/model/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode delete(@RequestBody JsonNode body) {
        String modelName = body.path("model_name").asText(null);
        if (modelName == null) {
            throw new BadRequestException("'model_name' is required", null, null);
        }
        boolean removedFromRouter = router.removeGroup(modelName);
        int removedRows = store.deleteByModelName(modelName);
        if (!removedFromRouter && removedRows == 0) {
            throw new NotFoundException("model '" + modelName + "' not found", null, modelName);
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("model_name", modelName);
        response.put("deleted", true);
        return response;
    }
}
