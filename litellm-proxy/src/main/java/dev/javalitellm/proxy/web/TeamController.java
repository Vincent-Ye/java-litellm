package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.proxy.teams.Team;
import dev.javalitellm.proxy.teams.TeamService;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Team management, master-key only (enforced by AuthFilter via /team/ prefix). */
@RestController
public class TeamController {

    private final TeamService teams;
    private final ObjectMapper mapper;

    public TeamController(TeamService teams, ObjectMapper mapper) {
        this.teams = teams;
        this.mapper = mapper;
    }

    @PostMapping(value = "/team/new", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode create(@RequestBody(required = false) JsonNode body) {
        JsonNode req = body == null ? mapper.createObjectNode() : body;
        Team team = teams.create(
                req.path("team_alias").asText(null),
                req.hasNonNull("max_budget")
                        ? BigDecimal.valueOf(req.get("max_budget").asDouble())
                        : null,
                req.hasNonNull("tpm_limit") ? req.get("tpm_limit").asInt() : null,
                req.hasNonNull("rpm_limit") ? req.get("rpm_limit").asInt() : null);
        return toJson(team);
    }

    @PostMapping(value = "/team/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode info(@RequestBody JsonNode body) {
        return toJson(team(body));
    }

    @PostMapping(value = "/team/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode update(@RequestBody JsonNode body) {
        String teamId = teamId(body);
        boolean updated = teams.update(
                teamId,
                body.hasNonNull("max_budget")
                        ? BigDecimal.valueOf(body.get("max_budget").asDouble())
                        : null,
                body.hasNonNull("tpm_limit") ? body.get("tpm_limit").asInt() : null,
                body.hasNonNull("rpm_limit") ? body.get("rpm_limit").asInt() : null,
                body.hasNonNull("blocked") ? body.get("blocked").asBoolean() : null);
        if (!updated) {
            throw new NotFoundException("team not found", null, null);
        }
        return toJson(teams.find(teamId).orElseThrow());
    }

    @PostMapping(value = "/team/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode delete(@RequestBody JsonNode body) {
        if (!teams.delete(teamId(body))) {
            throw new NotFoundException("team not found", null, null);
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("deleted", true);
        return response;
    }

    private Team team(JsonNode body) {
        return teams.find(teamId(body)).orElseThrow(() -> new NotFoundException("team not found", null, null));
    }

    private String teamId(JsonNode body) {
        String teamId = body.path("team_id").asText(null);
        if (teamId == null) {
            throw new BadRequestException("'team_id' is required", null, null);
        }
        return teamId;
    }

    private ObjectNode toJson(Team team) {
        ObjectNode node = mapper.createObjectNode();
        node.put("team_id", team.teamId());
        node.put("team_alias", team.alias());
        node.put(
                "max_budget", team.maxBudget() == null ? null : team.maxBudget().doubleValue());
        node.put("spend", team.spend() == null ? 0d : team.spend().doubleValue());
        node.put("tpm_limit", team.tpmLimit());
        node.put("rpm_limit", team.rpmLimit());
        node.put("blocked", team.blocked());
        return node;
    }
}
