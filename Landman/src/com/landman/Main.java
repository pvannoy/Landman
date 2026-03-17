package com.landman;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Main program that prompts the user to select an Excel file via a file explorer
 * dialog. The file must contain columns Name, City, and St (header row can be at
 * any position). Each row is searched on TruePeopleSearch and the results
 * (phones_tps, emails_tps) are written to found_data.xlsx in the same directory
 * as the input file.
 */
public class Main {

    private static final int DEFAULT_DELAY = 5;

    public static void main(String[] args) {
        // Ensure Swing dialogs use the native OS look-and-feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        //TODO: See if this file explore will work on both Windows and Mac (and Linux?). If not, may need to implement separate file selection for each OS.
        // ── Prompt user to select an Excel file ──────────────────────────────
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Excel File");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Excel Files (*.xlsx, *.xls)", "xlsx", "xls"));
        fileChooser.setAcceptAllFileFilterUsed(false);

        int result = fileChooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            System.out.println("No file selected. Exiting.");
            System.exit(0);
        }

        File selectedFile = fileChooser.getSelectedFile();
        String inputPath = selectedFile.getAbsolutePath();
        String outputPath = Paths.get(selectedFile.getParent(), "found_data.xlsx").toString();

        int delay = DEFAULT_DELAY;
        if (args.length >= 1) {
            try {
                delay = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("Selected file: " + inputPath);
        System.out.println("Output will be written to: " + outputPath);

        // ── Initialize the shared browser session (solve CAPTCHAs once) ─────
        TruePeopleSearchScraper.initialize();

        // ── Process the Excel file ───────────────────────────────────────────
        try (FileInputStream fis = new FileInputStream(inputPath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            // ── Locate the header row by scanning for known column names ─────
            Row headerRow = null;
            int nameCol = -1, cityCol = -1, stCol = -1;
            int phonesTpsCol = -1, emailsTpsCol = -1;

            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row candidate = sheet.getRow(r);
                if (candidate == null) continue;

                //TODO: Make the switch case-insensitive and ignore whitespace in header matching
                for (int c = 0; c < candidate.getLastCellNum(); c++) {
                    Cell cell = candidate.getCell(c);
                    if (cell == null || cell.getCellType() != CellType.STRING) continue;
                    String header = cell.getStringCellValue().trim().toLowerCase();
                    switch (header) {
                        case "name" -> nameCol = c;
                        case "city" -> cityCol = c;
                        case "st","state" -> stCol = c;
                        case "phone" -> phonesTpsCol = c;
                        case "email" -> emailsTpsCol = c;
                    }
                }

                // Found the header row once we see the three required columns
                if (nameCol != -1 && cityCol != -1 && stCol != -1) {
                    headerRow = candidate;
                    break;
                }

                // Reset for next row attempt
                nameCol = -1;
                cityCol = -1;
                stCol = -1;
                phonesTpsCol = -1;
                emailsTpsCol = -1;
            }

            if (headerRow == null) {
                JOptionPane.showMessageDialog(null,
                        "Could not find a header row with columns: Name, City, St",
                        "Missing Columns", JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            }

            int headerRowIndex = headerRow.getRowNum();
            int lastCol = headerRow.getLastCellNum();

            System.out.println("Found header row at row " + headerRowIndex);

            // Add output columns if they don't exist yet
            if (phonesTpsCol == -1) {
                phonesTpsCol = lastCol;
                headerRow.createCell(phonesTpsCol).setCellValue("Phone");
                lastCol++;
            }
            if (emailsTpsCol == -1) {
                emailsTpsCol = lastCol;
                headerRow.createCell(emailsTpsCol).setCellValue("Email");
            }

            // ── Process each data row (starting after the header) ────────────
            int totalRows = sheet.getLastRowNum();
            for (int r = headerRowIndex + 1; r <= totalRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String name = getCellString(row.getCell(nameCol));
                String city = getCellString(row.getCell(cityCol));
                String state = getCellString(row.getCell(stCol));

                if (name.isEmpty() || city.isEmpty() || state.isEmpty()) {
                    System.out.println("Skipping row " + r + " (missing data)");
                    continue;
                }

                System.out.println("Searching for: " + name + ", " + city + ", " + state);
                System.out.println("Searching in TruePeopleSearch...");

                TruePeopleSearchScraper.PersonContact contact =
                        TruePeopleSearchScraper.search(name, city, state);

                String phonesStr = String.join("\n", contact.phones);
                String emailsStr = String.join("\n", contact.emails);

                getOrCreateCell(row, phonesTpsCol).setCellValue(phonesStr);
                getOrCreateCell(row, emailsTpsCol).setCellValue(emailsStr);

                System.out.println("Found phones(tps): " + contact.phones);
                System.out.println("Found emails(tps): " + contact.emails);

                // Randomize delay between searches (±50% jitter around base)
                // Fixed intervals are a strong bot signal
                Random rng = new Random();
                int jitteredDelay = (int) (delay * (0.5 + rng.nextDouble() * 1.0));
                jitteredDelay = Math.max(2, jitteredDelay); // at least 2 seconds
                System.out.println("Sleeping for " + jitteredDelay + " seconds...");
                Thread.sleep(jitteredDelay * 1000L);
                System.out.println("--------------------");
            }

            // ── Write output ─────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }

            System.out.println("Results written to: " + outputPath);
            JOptionPane.showMessageDialog(null,
                    "Done! Results written to:\n" + outputPath,
                    "Complete", JOptionPane.INFORMATION_MESSAGE);

        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + inputPath);
            JOptionPane.showMessageDialog(null,
                    "File not found: " + inputPath,
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            JOptionPane.showMessageDialog(null,
                    "Error: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        } finally {
            TruePeopleSearchScraper.shutdown();
        }
    }

    /** Safely read a cell value as a trimmed String. */
    private static String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** Get or create a cell at the given column index. */
    private static Cell getOrCreateCell(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        return cell;
    }
}