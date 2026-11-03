package com.example.ragapp.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.ragapp.retrieval.RetrievedChunk;
import com.example.ragapp.retrieval.TextWords;
import org.springframework.stereotype.Component;

/**
 * Decides which retrieved chunks an answer is really based on.
 *
 * Why not just ask the model to cite its sources? Small local models often write the wrong
 * source number. So the application checks it itself: for every chunk it measures how many of
 * the answer's words and numbers appear in that chunk. A chunk that contains most of the
 * answer's words is a real source. This also means the sources can be trusted.
 *
 * Limit: this compares words, so it only works when the answer is in the same (Latin script)
 * language as the documents. For other languages the check is skipped.
 */
@Component
public class AnswerAttribution {

    private static final double MIN_OVERLAP = 0.30;        // below this a chunk is not a source
    private static final double RELATIVE_TO_BEST = 0.60;   // other sources must be close to the best one
    private static final int MAX_SOURCES = 3;

    /**
     * Returns the positions (0-based) of the supporting chunks, best first.
     *
     * An EMPTY list means: the answer is not supported by any chunk (its words are not in the
     * documents). The caller must then not show the answer. This also acts as a safety net when
     * the model ignores its rules (for example because of an injected instruction).
     */
    public List<Integer> selectSupportingChunks(String answer, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        Set<String> answerWords = words(answer);
        if (answerWords.isEmpty() || !isMostlyLatinText(answer)) {
            // Too short, or in another language/script: words cannot be compared with the
            // (English) documents, so we cannot check. Show the top retrieval result.
            return List.of(0);
        }

        List<Set<String>> chunkWords = chunks.stream().map(chunk -> words(chunk.text())).toList();

        // A word that appears in every chunk (like the document title) says nothing about which
        // chunk the answer came from, so rare words count more: weight = 1 / number of chunks containing it.
        Map<String, Double> weights = new HashMap<>();
        double totalWeight = 0;
        for (String word : answerWords) {
            long chunksWithWord = chunkWords.stream().filter(words -> words.contains(word)).count();
            double weight = 1.0 / Math.max(1, chunksWithWord);
            weights.put(word, weight);
            totalWeight += weight;
        }

        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double sharedWeight = 0;
            for (String word : answerWords) {
                if (chunkWords.get(i).contains(word)) {
                    sharedWeight += weights.get(word);
                }
            }
            matches.add(new Match(i, sharedWeight / totalWeight));
        }
        matches.sort(Comparator.comparingDouble(Match::overlap).reversed());

        double best = matches.get(0).overlap();
        if (best < MIN_OVERLAP) {
            return List.of();   // not supported by the documents
        }
        List<Match> candidates = matches.stream()
                .filter(match -> match.overlap() >= MIN_OVERLAP && match.overlap() >= best * RELATIVE_TO_BEST)
                .toList();

        // The best chunk is always a source. Another chunk is only added when it covers some of the
        // answer's words that the sources before it do not (so two pages that both contain the
        // same short answer words do not both appear).
        List<Integer> selected = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (Match candidate : candidates) {
            Set<String> supportedWords = new HashSet<>(answerWords);
            supportedWords.retainAll(chunkWords.get(candidate.position()));

            boolean addsSomethingNew = !covered.containsAll(supportedWords);
            if (selected.isEmpty() || addsSomethingNew) {
                selected.add(candidate.position());
                covered.addAll(supportedWords);
            }
            if (selected.size() == MAX_SOURCES) {
                break;
            }
        }
        return selected;
    }

    /**
     * Picks the sentence of the chunk that matches the answer best, to show as the quote.
     * (A chunk can be a whole page, and the user wants to see the relevant line.)
     */
    public String bestPassage(String answer, String chunkText, int maxLength) {
        Set<String> answerWords = words(answer);
        String best = "";
        double bestScore = -1;
        for (String sentence : chunkText.split("(?<=[.!?])\\s+|\\n+")) {
            double score = overlap(answerWords, words(sentence));
            if (score > bestScore) {
                bestScore = score;
                best = sentence;
            }
        }
        String clean = best.replaceAll("\\s+", " ").strip();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength) + "...";
    }

    /** Share of the answer's words that also appear in the chunk (0 to 1). */
    private double overlap(Set<String> answerWords, Set<String> chunkWords) {
        if (answerWords.isEmpty()) {
            return 0;
        }
        long shared = answerWords.stream().filter(chunkWords::contains).count();
        return (double) shared / answerWords.size();
    }

    /** True when at least 70 percent of the letters are Latin (A-Z). Tamil, Hindi etc. return false. */
    private boolean isMostlyLatinText(String text) {
        long letters = text.chars().filter(Character::isLetter).count();
        long latin = text.chars()
                .filter(Character::isLetter)
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN)
                .count();
        return letters == 0 || latin >= letters * 0.7;
    }

    private Set<String> words(String text) {
        return TextWords.words(text);
    }

    private record Match(int position, double overlap) {
    }
}
