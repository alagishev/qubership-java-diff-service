package org.qubership.jdiff.render;

import java.nio.file.Path;
import org.qubership.jdiff.model.DiffReport;

/**
 * Renders a {@link DiffReport} into a human-readable file format.
 */
public interface ReportRenderer {

    /**
     * @return the format identifier, e.g. {@code "html"}, {@code "csv"}, {@code "xlsx"}
     */
    String format();

    /**
     * @return the file name to write the rendered report to, e.g. {@code "report.html"}
     */
    String fileName();

    /**
     * Renders {@code report} into {@code outputFile}.
     *
     * @param report     the report to render
     * @param outputFile the file to write the rendered report to
     */
    void render(DiffReport report, Path outputFile);
}
