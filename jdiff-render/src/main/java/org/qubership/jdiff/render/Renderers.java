package org.qubership.jdiff.render;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Factory for the {@link ReportRenderer} implementations available in this module.
 */
public final class Renderers {

    private static final Map<String, Supplier<ReportRenderer>> FACTORIES = Map.of(
            "html", HtmlReportRenderer::new,
            "csv", CsvReportRenderer::new,
            "xlsx", XlsxReportRenderer::new);

    private Renderers() {
    }

    /**
     * @param format the report format, one of {@link #supportedFormats()}
     * @return a fresh {@link ReportRenderer} for {@code format}
     * @throws IllegalArgumentException if {@code format} is not supported
     */
    public static ReportRenderer forFormat(String format) {
        Supplier<ReportRenderer> factory = FACTORIES.get(format);
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported render format: " + format
                    + " (supported: " + supportedFormats() + ")");
        }
        return factory.get();
    }

    /**
     * @return the set of format identifiers supported by {@link #forFormat(String)}
     */
    public static Set<String> supportedFormats() {
        return FACTORIES.keySet();
    }
}
