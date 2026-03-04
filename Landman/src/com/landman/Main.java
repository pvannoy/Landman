package com.landman;

import com.microsoft.playwright.Playwright;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Paths;

/**
 * Main program that reads an Excel file (default: data.xlsx) containing columns
 * Name, City, and St, searches TruePeopleSearch for each row, and writes the
 * results (phones_tps, emails_tps) to found_data.xlsx.
 *
 * Usage:
 *   java -cp "<classpath>" com.landman.Main [filename] [delay]
 *
 *   filename – Excel file to process (default: data.xlsx)
 *   delay    – seconds to sleep between searches (default: 5)
 */
public class Main {

    public static void main(String[] args) {
        String filename = args.length >= 1 ? args[0] : "data.xlsx";
        int delay = args.length >= 2 ? Integer.parseInt(args[1]) : 5;

        String inputPath = Paths.get(System.getProperty("user.dir"), filename).toString();
        String outputPath = Paths.get(System.getProperty("user.dir"), "found_data.xlsx").toString();

        System.out.println("Reading input file: " + inputPath);

        try (FileInputStream fis = new FileInputStream(inputPath);
             Workbook workbook = new XSSFWorkbook(fis);
             Playwright playwright = Playwright.create()) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            // ── Locate required columns by name ──────────────────────────────
            int nameCol = -1, cityCol = -1, stCol = -1;
            int phonesTpsCol = -1, emailsTpsCol = -1;
            int lastCol = headerRow.getLastCellNum();

            for (int c = 0; c < lastCol; c++) {
                Cell cell = headerRow.getCell(c);
                if (cell == null) continue;
                String header = cell.getStringCellValue().trim();
                switch (header) {
                    case "Name" -> nameCol = c;
                    case "City" -> cityCol = c;
                    case "St" -> stCol = c;
                    case "phones_tps" -> phonesTpsCol = c;
                    case "emails_tps" -> emailsTpsCol = c;
                }
            }

            if (nameCol == -1 || cityCol == -1 || stCol == -1) {
                System.err.println("ERROR: Excel file must have columns: Name, City, St");
                System.exit(2);
            }

            // Add output columns if they don't exist yet
            if (phonesTpsCol == -1) {
                phonesTpsCol = lastCol;
                headerRow.createCell(phonesTpsCol).setCellValue("phones_tps");
                lastCol++;
            }
            if (emailsTpsCol == -1) {
                emailsTpsCol = lastCol;
                headerRow.createCell(emailsTpsCol).setCellValue("emails_tps");
            }

            // ── Process each data row ────────────────────────────────────────
            int totalRows = sheet.getLastRowNum(); // 0-based index of last row
            for (int r = 1; r <= totalRows; r++) {
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
                        TruePeopleSearchScraper.search(playwright, name, city, state);

                String phonesStr = String.join("\n", contact.phones);
                String emailsStr = String.join("\n", contact.emails);

                getOrCreateCell(row, phonesTpsCol).setCellValue(phonesStr);
                getOrCreateCell(row, emailsTpsCol).setCellValue(emailsStr);

                System.out.println("Found phones(tps): " + contact.phones);
                System.out.println("Found emails(tps): " + contact.emails);

                System.out.println("Sleeping for " + delay + " seconds...");
                Thread.sleep(delay * 1000L);
                System.out.println("--------------------");
            }

            // ── Write output ─────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }
            System.out.println("Results written to: " + outputPath);

        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + inputPath);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
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
}
