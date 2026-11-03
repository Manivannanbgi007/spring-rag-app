package com.example.ragapp.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.ragapp.retrieval.RankFusion.FusedHit;
import com.example.ragapp.retrieval.Reranker.RankedHit;
import org.junit.jupiter.api.Test;

class RerankerTest {

    private final Reranker reranker = new Reranker();

    @Test
    void chunkThatContainsTheQuestionWordsBeatsAChunkThatIsOnlyCloserInMeaning() {
        // The annual leave chunk is closer in meaning (0.55 against 0.50) because of "leave" and "days",
        // but only the sick leave chunk contains "sick", the word that tells the two apart.
        FusedHit annual = candidate("All full-time employees are entitled to 24 days of paid annual leave.", 0.55);
        FusedHit sick = candidate("Employees receive 10 days of paid sick leave per year.", 0.50);

        List<RankedHit> ranked = reranker.rerank("How many days of sick leave?", List.of(annual, sick));

        assertThat(ranked.get(0).hit()).isEqualTo(sick.hit());
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void scoresStayBetweenZeroAndOne() {
        List<RankedHit> ranked = reranker.rerank("sick leave", List.of(
                candidate("Sick leave is 10 days.", 1.3), candidate("Unrelated text about parking.", -0.2)));

        assertThat(ranked).allSatisfy(r -> assertThat(r.score()).isBetween(0.0, 1.0));
    }

    @Test
    void questionWithoutUsableWordsFallsBackToMeaning() {
        List<RankedHit> ranked = reranker.rerank("???", List.of(
                candidate("Text A.", 0.4), candidate("Text B.", 0.6)));

        assertThat(ranked.get(0).hit().text()).isEqualTo("Text B.");
    }

    @Test
    void emptyListGivesEmptyList() {
        assertThat(reranker.rerank("anything", List.of())).isEmpty();
    }

    private FusedHit candidate(String text, double similarity) {
        SearchHit hit = new SearchHit(UUID.randomUUID(), text, Map.of(), similarity, 0);
        return new FusedHit(hit, 0.01, true, false);
    }
}
