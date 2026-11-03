package com.example.ragapp.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/** The regression questions, read from src/test/resources/evaluation/eval-set.json. */
public record EvalSet(String description, List<EvalCase> cases) {

    public static EvalSet load() {
        try (InputStream in = EvalSet.class.getResourceAsStream("/evaluation/eval-set.json")) {
            return JsonMapper.builder().build().readValue(in, EvalSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<EvalCase> ofType(EvalCase.Type... types) {
        List<EvalCase.Type> wanted = List.of(types);
        return cases.stream().filter(c -> wanted.contains(c.type())).toList();
    }
}
