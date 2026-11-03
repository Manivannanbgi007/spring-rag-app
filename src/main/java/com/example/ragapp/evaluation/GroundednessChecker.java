package com.example.ragapp.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.ragapp.retrieval.TextWords;
import org.springframework.stereotype.Component;

/**
 * Checks whether the claims in an answer are supported by the evidence (the text of the documents).
 *
 * "Grounded" means: everything the answer says can be found in the documents. A model that makes up
 * a number or adds a fact from its own memory is not grounded, even if the sentence sounds right.
 *
 * How it works (simple on purpose, and it needs no model):
 *   1. The answer is cut into claims (sentences and list items).
 *   2. For each claim, the evidence sentence that matches it best is found (the "best evidence").
 *      If a second sentence matches almost as well, it is used too (an answer can combine two sentences).
 *   3. A claim is SUPPORTED when, looking only at that best evidence,
 *        - every number of the claim is there ("3 days" against "2 days" fails), and
 *        - at least 80 percent of its words are there (numbers and e-mail addresses that match count as words
 *          that match, and when the claim has such exact items, 60 percent is enough: an answer that is mainly
 *          a phone number or an address can use a different verb than the text, "reach" instead of "contact").
 *   4. The groundedness score = supported claims / all claims.
 *
 * Why the best evidence and not the whole page? A page contains many numbers. "Employees may work 3 days per week"
 * must not pass just because a heading on the same page happens to say "3."
 *
 * Limits: it compares words, so a correct answer with very different wording can be marked unsupported, and a wrong
 * claim that SWAPS the facts of the text ("1 month and 3 months" turned around) can be marked supported.
 * That is why the evaluation also looks at a judge model as a second opinion, and why that judge is
 * measured against answers labelled by a person (see JudgeCalibrationTest in the tests).
 */
@Component
public class GroundednessChecker {

    private static final double MIN_WORD_SUPPORT = 0.80;
    private static final double MIN_SUPPORT_WITH_EXACT_ITEMS = 0.60;   // lower, because numbers and e-mail addresses matched exactly
    private static final double SECOND_EVIDENCE_SHARE = 0.60;   // a second sentence counts when it matches at least this well compared to the best one
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*\\d|\\d");
    private static final Pattern CLAIM_SEPARATOR = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    /**
     * @param totalClaims       number of claims found in the answer
     * @param supportedClaims   how many of them the evidence supports
     * @param unsupportedClaims the claims that are not supported (to show in a report)
     */
    public record Result(int totalClaims, int supportedClaims, List<String> unsupportedClaims) {

        /** 1.0 when everything is supported (or there is nothing to check), 0.0 when nothing is. */
        public double score() {
            return totalClaims == 0 ? 1.0 : (double) supportedClaims / totalClaims;
        }
    }

    /** One sentence of the evidence, with its words and numbers worked out once. */
    private record EvidenceUnit(Set<String> words, Set<String> numbers, Set<String> emails) {
    }

    public Result check(String answer, List<String> evidence) {
        List<EvidenceUnit> units = new ArrayList<>();
        for (String text : evidence) {
            for (String sentence : CLAIM_SEPARATOR.split(text)) {
                if (!sentence.isBlank()) {
                    units.add(new EvidenceUnit(wordsWithoutEmails(sentence), numbersIn(withoutEmails(sentence)), emailsIn(sentence)));
                }
            }
        }

        List<String> unsupported = new ArrayList<>();
        int total = 0;
        for (String claim : claimsOf(answer)) {
            total++;
            if (!isSupported(claim, units)) {
                unsupported.add(claim);
            }
        }
        return new Result(total, total - unsupported.size(), unsupported);
    }

    private boolean isSupported(String claim, List<EvidenceUnit> units) {
        // Numbers and e-mail addresses are exact items: they either match or they do not. They are checked
        // separately from the other words.
        Set<String> claimEmails = emailsIn(claim);
        Set<String> claimWords = new HashSet<>(wordsWithoutEmails(claim));
        claimWords.removeIf(word -> word.chars().allMatch(Character::isDigit));
        Set<String> claimNumbers = numbersIn(withoutEmails(claim));

        // the best evidence: the sentences that share most words with the claim
        List<EvidenceUnit> ranked = units.stream()
                .sorted(Comparator.comparingLong(
                        (EvidenceUnit unit) -> sharedItems(claimWords, claimNumbers, claimEmails, unit)).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return false;
        }
        long bestShared = sharedItems(claimWords, claimNumbers, claimEmails, ranked.get(0));
        Set<String> evidenceWords = new HashSet<>(ranked.get(0).words());
        Set<String> evidenceNumbers = new HashSet<>(ranked.get(0).numbers());
        Set<String> evidenceEmails = new HashSet<>(ranked.get(0).emails());
        if (ranked.size() > 1 && bestShared > 0 && sharedItems(claimWords, claimNumbers, claimEmails, ranked.get(1)) >= bestShared * SECOND_EVIDENCE_SHARE) {
            evidenceWords.addAll(ranked.get(1).words());
            evidenceNumbers.addAll(ranked.get(1).numbers());
            evidenceEmails.addAll(ranked.get(1).emails());
        }

        if (!evidenceNumbers.containsAll(claimNumbers) || !evidenceEmails.containsAll(claimEmails)) {
            return false;
        }
        // Numbers that match exactly are strong proof, so they count as supported words too. Without this, one
        // paraphrased word ("reach" for "contact") would sink a claim whose phone number matches digit for digit.
        int exactItems = claimNumbers.size() + claimEmails.size();
        int checkedItems = claimWords.size() + exactItems;
        if (checkedItems == 0) {
            return true;
        }
        long found = claimWords.stream().filter(evidenceWords::contains).count() + exactItems;
        double needed = exactItems > 0 ? MIN_SUPPORT_WITH_EXACT_ITEMS : MIN_WORD_SUPPORT;
        return (double) found / checkedItems >= needed;
    }

    /** How many words, numbers and e-mail addresses of the claim a sentence of the evidence contains. */
    private long sharedItems(Set<String> claimWords, Set<String> claimNumbers, Set<String> claimEmails, EvidenceUnit unit) {
        return claimWords.stream().filter(unit.words()::contains).count()
                + claimNumbers.stream().filter(unit.numbers()::contains).count()
                + claimEmails.stream().filter(unit.emails()::contains).count();
    }

    private Set<String> emailsIn(String text) {
        Set<String> emails = new HashSet<>();
        Matcher matcher = EMAIL.matcher(text);
        while (matcher.find()) {
            emails.add(matcher.group().toLowerCase());
        }
        return emails;
    }

    private String withoutEmails(String text) {
        return EMAIL.matcher(text).replaceAll(" ");
    }

    /** The words of a text, but not the pieces of an e-mail address ("hr", "help", "example" are not words here). */
    private Set<String> wordsWithoutEmails(String text) {
        return TextWords.words(withoutEmails(text));
    }

    /** Sentences and list items with at least 3 words. Shorter pieces ("Yes.", "Sources:") are not claims. */
    private List<String> claimsOf(String answer) {
        List<String> claims = new ArrayList<>();
        for (String piece : CLAIM_SEPARATOR.split(answer)) {
            String claim = piece.replaceAll("^[\\s*\\-\\u2022\\d.)]+", "").strip();
            if (claim.split("\\s+").length >= 3) {
                claims.add(claim);
            }
        }
        return claims;
    }

    /** "1,000" and "1000" are the same number; a full stop at the end of a sentence is not part of it. */
    private Set<String> numbersIn(String text) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group().replace(",", "").replaceAll("\\.$", ""));
        }
        return numbers;
    }
}
