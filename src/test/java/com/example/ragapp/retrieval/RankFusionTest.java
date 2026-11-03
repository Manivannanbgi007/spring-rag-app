package com.example.ragapp.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.ragapp.retrieval.RankFusion.FusedHit;
import org.junit.jupiter.api.Test;

class RankFusionTest {

    private final SearchHit a = hit("A");
    private final SearchHit b = hit("B");
    private final SearchHit c = hit("C");
    private final SearchHit d = hit("D");

    @Test
    void chunkNearTheTopOfBothListsWins() {
        List<FusedHit> fused = RankFusion.fuse(List.of(a, b, c), List.of(c, d), 60);

        assertThat(fused.get(0).hit()).isEqualTo(c);   // 3rd in one list, 1st in the other
        assertThat(fused.get(0).foundByVector()).isTrue();
        assertThat(fused.get(0).foundByText()).isTrue();
        assertThat(fused.get(0).rrfScore()).isEqualTo(1.0 / 63 + 1.0 / 61);
    }

    @Test
    void chunkFoundByOnlyOneSearchIsKept() {
        // "d" is only found by the keyword search, for example a policy code the meaning search does not know
        List<FusedHit> fused = RankFusion.fuse(List.of(a, b), List.of(d), 60);

        FusedHit onlyByText = fused.stream().filter(f -> f.hit().equals(d)).findFirst().orElseThrow();
        assertThat(onlyByText.foundByText()).isTrue();
        assertThat(onlyByText.foundByVector()).isFalse();
        assertThat(fused).hasSize(3);
    }

    @Test
    void noKeywordResultsKeepsTheVectorOrder() {
        List<FusedHit> fused = RankFusion.fuse(List.of(a, b, c), List.of(), 60);

        assertThat(fused).extracting(FusedHit::hit).containsExactly(a, b, c);
    }

    @Test
    void bothListsEmptyGivesNothing() {
        assertThat(RankFusion.fuse(List.of(), List.of(), 60)).isEmpty();
    }

    private SearchHit hit(String text) {
        return new SearchHit(UUID.randomUUID(), text, Map.of(), 0.5, 0);
    }
}
