package com.example.ragapp.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import com.example.ragapp.evaluation.AnswerEvaluator.CaseResult;

/** Writes the result of an evaluation as a readable Markdown file: target/evaluation/&lt;name&gt;.md */
public final class EvalReport {

    private static final Path DIRECTORY = Path.of("target", "evaluation");

    private EvalReport() {
    }

    public static Path write(String name, String model, EvalSummary summary, List<CaseResult> results,
                             List<String> violations, String extraSections) {
        StringBuilder md = new StringBuilder();
        md.append("# Evaluation report: ").append(name).append("\n\n");
        md.append("Time: ").append(LocalDateTime.now().withNano(0)).append("  \nModel: ").append(model).append("\n\n");

        md.append("## Summary\n\n| Measure | Value | Gate |\n|---|---|---|\n");
        row(md, "Questions passed", summary.passed() + " of " + summary.questions(), "-");
        row(md, "Fact recall (answerable questions)", pct(summary.factRecall()), ">= " + pct(QualityGates.MIN_FACT_RECALL));
        row(md, "Citation accuracy", pct(summary.citationAccuracy()), ">= " + pct(QualityGates.MIN_CITATION_ACCURACY));
        row(md, "Correct refusals (unanswerable)", pct(summary.correctRefusals()), ">= " + pct(QualityGates.MIN_CORRECT_REFUSALS));
        row(md, "False refusals (answerable)", pct(summary.falseRefusals()), "<= " + pct(QualityGates.MAX_FALSE_REFUSALS));
        row(md, "Injection resistance", pct(summary.injectionResistance()), ">= " + pct(QualityGates.MIN_INJECTION_RESISTANCE));
        row(md, "Groundedness", pct(summary.groundedness()), ">= " + pct(QualityGates.MIN_GROUNDEDNESS));
        row(md, "Latency p50 / p95", summary.p50Millis() + " ms / " + summary.p95Millis() + " ms", "p95 <= " + QualityGates.MAX_P95_MILLIS + " ms");
        row(md, "Average cost per question", String.format("%.5f USD", summary.averageCostUsd()), "<= " + QualityGates.MAX_AVERAGE_COST_USD + " USD");
        md.append("\n**Gates: ").append(violations.isEmpty() ? "ALL PASSED" : "FAILED").append("**\n");
        violations.forEach(v -> md.append("\n- ").append(v));

        md.append("\n\n## Questions\n\n| Id | Type | Result | ms | Groundedness | Notes |\n|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            md.append("| ").append(r.evalCase().id()).append(" | ").append(r.evalCase().type()).append(" | ")
                    .append(r.passed() ? "pass" : "**FAIL**").append(" | ").append(r.millis()).append(" | ")
                    .append(r.groundedness() == null ? "-" : pct(r.groundedness())).append(" | ")
                    .append(r.passed() ? "" : String.join("; ", r.problems()).replace("|", "/")).append(" |\n");
        }
        md.append(extraSections);

        try {
            Files.createDirectories(DIRECTORY);
            Path file = DIRECTORY.resolve(name + ".md");
            Files.writeString(file, md.toString());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(StringBuilder md, String measure, String value, String gate) {
        md.append("| ").append(measure).append(" | ").append(value).append(" | ").append(gate).append(" |\n");
    }

    public static String pct(double value) {
        return String.format("%.0f%%", value * 100);
    }
}
