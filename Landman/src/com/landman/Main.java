package com.landman;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.nio.file.Paths;

/**
 * Main program that prompts the user to select an Excel file via a file explorer
 * dialog. The file must contain columns Name, City, and St (header row can be at
 * any position). Each row is searched on TruePeopleSearch and the results
 * (phones_tps, emails_tps) are written to found_data.xlsx in the same directory
 * as the input file.
 *
 * Resume behaviour:
 *   If found_data.xlsx already exists from a previous run, rows that already have
 *   a Phone or Email value are skipped automatically. This means you can re-run
 *   the program after an IP ban and it will pick up exactly where it left off.
 *
 * IP ban behaviour:
 *   If TruePeopleSearch bans the IP and the ban does not lift within 15 minutes,
 *   the program saves all progress collected so far and exits cleanly. Re-run
 *   once the ban has lifted to collect the remaining rows.
 */
public class Main {

    private static final int DEFAULT_DELAY = 45;

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
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
        String selectedPath = selectedFile.getAbsolutePath();
        String selectedName = selectedFile.getName();

        // ── Derive the input and output paths from whichever file was selected ─
        //
        // The user may select either:
        //   A) The original input file  (e.g. "Grady - 32-8N-8W - Test.xlsx")
        //   B) A previous output file   (e.g. "Grady - 32-8N-8W - Test_found_data.xlsx")
        //
        // If (B) is selected we work directly with that file — inputPath and
        // outputPath both point to it, and existing results are preserved.
        // If (A) is selected and a _found_data file already exists beside it,
        // we resume from that file automatically.
        // If (A) is selected and no _found_data file exists, we start fresh.

        int dotIndex = selectedName.lastIndexOf('.');
        String baseName  = dotIndex > 0 ? selectedName.substring(0, dotIndex) : selectedName;
        String extension = dotIndex > 0 ? selectedName.substring(dotIndex)    : ".xlsx";

        String inputPath;
        String outputPath;

        if (baseName.endsWith("_found_data")) {
            // User selected the _found_data file directly — use it as both source and output
            inputPath  = selectedPath;
            outputPath = selectedPath;
            System.out.println("Resuming from selected output file: " + selectedPath);
        } else {
            // User selected the original input file
            inputPath  = selectedPath;
            outputPath = Paths.get(selectedFile.getParent(),
                    baseName + "_found_data" + extension).toString();

            if (new java.io.File(outputPath).exists()) {
                // A _found_data file already exists — resume from it
                inputPath = outputPath;
                System.out.println("Found existing output file — resuming from: " + outputPath);
            } else {
                System.out.println("No existing output file found — starting fresh.");
            }
        }

        int delay = DEFAULT_DELAY;
        if (args.length >= 1) {
            try {
                delay = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("Selected file: " + selectedPath);
        System.out.println("Output will be written to: " + outputPath);

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

                // ── Skip rows already completed in a previous run ─────────────
                // A row is considered done if it already has a non-empty Phone
                // or Email value. This enables resume-after-ban behaviour.
                String existingPhone = getCellString(row.getCell(phonesTpsCol));
                String existingEmail = getCellString(row.getCell(emailsTpsCol));
                if (!existingPhone.isEmpty() || !existingEmail.isEmpty()) {
                    System.out.println("Skipping row " + r + " (already completed): " + name);
                    continue;
                }

                System.out.println("Searching for: " + name + ", " + city + ", " + state);
                System.out.println("Searching in TruePeopleSearch...");

                // Retry loop — if the browser crashes mid-search, restart it and try once more.
                TruePeopleSearchScraper.PersonContact contact = null;
                int attempts = 0;
                boolean ipBanned = false;
                while (contact == null && attempts < 2) {
                    attempts++;
                    try {
                        contact = TruePeopleSearchScraper.search(name, city, state);
                    } catch (TruePeopleSearchScraper.IpBanException banEx) {
                        // IP ban did not lift — save progress and exit cleanly
                        System.out.println("IP ban could not be resolved. Saving progress and exiting.");
                        ipBanned = true;
                        break;
                    } catch (org.openqa.selenium.WebDriverException browserEx) {
                        System.out.println("Browser died (attempt " + attempts + "): " + browserEx.getMessage().split("\n")[0]);
                        if (attempts < 2) {
                            System.out.println("Restarting browser and retrying row...");
                            TruePeopleSearchScraper.restartDriver();
                            Thread.sleep(3000);
                        } else {
                            System.out.println("Skipping row after browser failed twice.");
                            contact = new TruePeopleSearchScraper.PersonContact();
                        }
                    }
                }

                // If banned, save what we have and exit so the user can resume later
                if (ipBanned) {
                    try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                        workbook.write(fos);
                    }
                    TruePeopleSearchScraper.quit();
                    System.out.println("Progress saved to: " + outputPath);
                    System.out.println("Re-run the program after the IP ban lifts to collect remaining rows.");
                    JOptionPane.showMessageDialog(null,
                            "IP ban could not be resolved.\n\nProgress saved to:\n" + outputPath
                            + "\n\nRe-run after the ban lifts to collect remaining rows.",
                            "Stopped — IP Banned", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                String phonesStr = String.join("\n", contact.phones);
                String emailsStr = String.join("\n", contact.emails);

                getOrCreateCell(row, phonesTpsCol).setCellValue(phonesStr);
                getOrCreateCell(row, emailsTpsCol).setCellValue(emailsStr);

                System.out.println("Found phones(tps): " + contact.phones);
                System.out.println("Found emails(tps): " + contact.emails);

                // Save progress to disk after every row so a crash doesn't lose work
                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    workbook.write(fos);
                }

                // Add random jitter (±15 s) so requests don't arrive on a fixed interval.
                // Fixed intervals are a strong bot signal; human browsing is irregular.
                int jitter = (int)(Math.random() * 30) - 15;  // -15 to +15 seconds
                int actualDelay = Math.max(20, delay + jitter); // never less than 20 s
                System.out.println("Sleeping for " + actualDelay + " seconds...");
                Thread.sleep(actualDelay * 1000L);
                System.out.println("--------------------");
            }

            // ── Write output ─────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("Results written to: " + outputPath);
            System.out.println("Total run time: " + formatElapsed(elapsed));

            // Close the shared browser now that all rows have been processed
            TruePeopleSearchScraper.quit();

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
            TruePeopleSearchScraper.quit();
            JOptionPane.showMessageDialog(null,
                    "Error: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
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

    /** Format elapsed milliseconds as a human-readable duration. */
    private static String formatElapsed(long millis) {
        long totalSeconds = millis / 1000;
        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0)
            return String.format("%d hr %d min %d sec", hours, minutes, seconds);
        else if (minutes > 0)
            return String.format("%d min %d sec", minutes, seconds);
        else
            return String.format("%d sec", seconds);
    }
}