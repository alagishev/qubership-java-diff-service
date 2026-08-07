package org.qubership.jdiff.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.resolve.EffectivePomBuilder;
import org.qubership.jdiff.resolve.ResolvedDependency;

class BomUpgradeExpanderTest {

    @Test
    void expandsOnlyTheDependencyWhoseManagedVersionChanged() throws URISyntaxException {
        Path fixturesDir = Path.of(getClass().getResource("/upgrade").toURI());
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(new FakeArtifactResolver(fixturesDir));
        BomUpgradeExpander expander = new BomUpgradeExpander(pomBuilder);

        Gav oldBom = new Gav("org.example", "bom", "old", null);
        List<ResolvedDependency> moduleDirectDeps = List.of(
                new ResolvedDependency(new Gav("org.example", "lib-a", "2.5", null), "compile", false),
                new ResolvedDependency(new Gav("org.example", "lib-b", "3.0", null), "compile", false));

        List<UpgradeItem> items = expander.expand(oldBom, "new", moduleDirectDeps);

        assertThat(items).containsExactly(new UpgradeItem("org.example", "lib-a", "2.5", "2.6"));
    }

    @Test
    void returnsEmptyWhenTheNewBomManagesNoDirectDependencyOfTheModule() throws URISyntaxException {
        Path fixturesDir = Path.of(getClass().getResource("/upgrade").toURI());
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(new FakeArtifactResolver(fixturesDir));
        BomUpgradeExpander expander = new BomUpgradeExpander(pomBuilder);

        Gav oldBom = new Gav("org.example", "bom", "old", null);
        List<ResolvedDependency> moduleDirectDeps = List.of(
                new ResolvedDependency(new Gav("org.example", "unrelated-lib", "1.0", null), "compile", false));

        List<UpgradeItem> items = expander.expand(oldBom, "new", moduleDirectDeps);

        assertThat(items).isEmpty();
    }
}
