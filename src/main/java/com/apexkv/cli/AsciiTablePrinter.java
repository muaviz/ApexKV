package com.apexkv.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Terminal ASCII table renderer. Formats tabular data with dynamic column widths,
 * aligned borders, headers, and clean dividers.
 */
public class AsciiTablePrinter {
    private final List<String> headers;
    private final List<List<String>> rows;
    private int maxColWidth = 60;

    public AsciiTablePrinter(String... headers) {
        this.headers = Arrays.asList(headers);
        this.rows = new ArrayList<>();
    }

    public AsciiTablePrinter setMaxColumnWidth(int width) {
        this.maxColWidth = Math.max(10, width);
        return this;
    }

    public void addRow(String... cells) {
        List<String> row = new ArrayList<>();
        for (String cell : cells) {
            row.add(cell != null ? cell : "");
        }
        rows.add(row);
    }

    public void print(PrintStream out) {
        if (headers.isEmpty() && rows.isEmpty()) {
            out.println("(Empty result set)");
            return;
        }

        int colCount = headers.size();
        for (List<String> row : rows) {
            colCount = Math.max(colCount, row.size());
        }

        int[] colWidths = new int[colCount];

        // Measure header widths
        for (int i = 0; i < headers.size(); i++) {
            colWidths[i] = Math.min(maxColWidth, Math.max(colWidths[i], headers.get(i).length()));
        }

        // Measure row widths
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                String val = row.get(i);
                colWidths[i] = Math.min(maxColWidth, Math.max(colWidths[i], val.length()));
            }
        }

        String separator = buildSeparator(colWidths);

        out.println(separator);
        if (!headers.isEmpty()) {
            out.println(buildRow(headers, colWidths));
            out.println(separator);
        }

        if (rows.isEmpty()) {
            out.println("| (0 rows matching query)" + padRight("", separator.length() - 26) + " |");
        } else {
            for (List<String> row : rows) {
                out.println(buildRow(row, colWidths));
            }
        }
        out.println(separator);
    }

    private String buildSeparator(int[] colWidths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : colWidths) {
            sb.append("-".repeat(width + 2)).append("+");
        }
        return sb.toString();
    }

    private String buildRow(List<String> cells, int[] colWidths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < colWidths.length; i++) {
            String cellVal = (i < cells.size()) ? cells.get(i) : "";
            if (cellVal.length() > colWidths[i]) {
                cellVal = cellVal.substring(0, colWidths[i] - 3) + "...";
            }
            sb.append(" ").append(padRight(cellVal, colWidths[i])).append(" |");
        }
        return sb.toString();
    }

    private String padRight(String s, int n) {
        if (s.length() >= n) {
            return s;
        }
        return s + " ".repeat(n - s.length());
    }
}
