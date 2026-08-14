package com.allog.verification.analysis.evaluation;

import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.template.VerificationTemplateCatalog;
import com.allog.verification.template.domain.VerificationTemplateKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads a versioned evaluation dataset from a tab-separated manifest.
 *
 * <p>TSV is used because a case is one flat line of short tokens: it parses with the JDK alone,
 * reviews as one line per case in a diff, and stays readable to the people who curate the labels.
 *
 * <p>The manifest holds curated ground truth only. Collected provider observations are deliberately
 * kept out of it, so that re-running an analysis can never rewrite the dataset it is measured
 * against.
 */
public final class EvaluationCaseManifest {

    static final String HEADER = "caseId\ttemplateKey\tcriteriaReference\tassetRef\thumanLabel";
    private static final int COLUMN_COUNT = 5;
    private static final String COMMENT_PREFIX = "#";

    private final List<EvaluationCase> cases;

    private EvaluationCaseManifest(List<EvaluationCase> cases) {
        this.cases = List.copyOf(cases);
    }

    public static EvaluationCaseManifest loadFromClasspath(String resourcePath, VerificationTemplateCatalog catalog) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        try (InputStream stream = EvaluationCaseManifest.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("evaluation manifest not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return parse(reader.lines().toList(), catalog);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static EvaluationCaseManifest parse(List<String> lines, VerificationTemplateCatalog catalog) {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");

        List<String> content = lines.stream()
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith(COMMENT_PREFIX))
                .toList();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("evaluation manifest must declare a header");
        }
        if (!HEADER.equals(content.getFirst())) {
            throw new IllegalArgumentException("evaluation manifest header must be: " + HEADER);
        }

        List<EvaluationCase> parsed = new ArrayList<>();
        Set<String> seenCaseIds = new LinkedHashSet<>();
        for (String row : content.subList(1, content.size())) {
            EvaluationCase evaluationCase = parseRow(row, catalog);
            if (!seenCaseIds.add(evaluationCase.caseId())) {
                throw new IllegalArgumentException("duplicate evaluation caseId: " + evaluationCase.caseId());
            }
            parsed.add(evaluationCase);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("evaluation manifest must declare at least one case");
        }
        return new EvaluationCaseManifest(parsed);
    }

    private static EvaluationCase parseRow(String row, VerificationTemplateCatalog catalog) {
        String[] columns = row.split("\t", -1);
        if (columns.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                    "evaluation manifest row must have " + COLUMN_COUNT + " tab-separated columns: " + row
            );
        }

        VerificationTemplateKey templateKey = new VerificationTemplateKey(columns[1]);
        VerificationCriteria.Reference criteriaReference =
                VerificationCriteria.Reference.fromStorageValue(columns[2]);
        // Reuses the production exact-pair rule, so an unknown template or a criteria reference that
        // is not the one the template pins is rejected by the same code the submit path relies on.
        catalog.resolve(templateKey, criteriaReference);

        return new EvaluationCase(
                columns[0],
                templateKey,
                criteriaReference,
                columns[3],
                humanLabel(columns[4])
        );
    }

    private static EvaluationHumanLabel humanLabel(String value) {
        Objects.requireNonNull(value, "humanLabel must not be null");
        try {
            return EvaluationHumanLabel.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown evaluation human label: " + value, exception);
        }
    }

    public List<EvaluationCase> cases() {
        return cases;
    }

    public int size() {
        return cases.size();
    }

    /** Counts per label, so a dataset gap is visible rather than silently skewing a sweep. */
    public long countOf(EvaluationHumanLabel label) {
        Objects.requireNonNull(label, "label must not be null");
        return cases.stream().filter(evaluationCase -> evaluationCase.humanLabel() == label).count();
    }
}
