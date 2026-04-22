package com.landman;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;

/**
 * Main program that prompts the user to select an Excel file via a file explorer
 * dialog. The file must contain columns Name, City, and St (header row can be at
 * any position). Each row is looked up via the Skip Sherpa API and the results
 * (Phone, Email) are written to found_data.xlsx in the same directory
 * as the input file.
 *
 * Resume behaviour:
 *   If found_data.xlsx already exists from a previous run, rows that already have
 *   a Phone or Email value are skipped automatically. This means you can re-run
 *   the program after an IP ban and it will pick up exactly where it left off.
 *
 * IP ban behaviour:
 *   If the Skip Sherpa API returns an error, the row is skipped and
 *   the program saves all progress collected so far and exits cleanly. Re-run
 *   once the ban has lifted to collect the remaining rows.
 */
public class Main {

    // ── Manual override for testing ───────────────────────────────────────
    // Set to true to force Skip Sherpa mode (skip API key validation).
    // Set to false (default) to force TruePeopleSearch fallback mode.
    // Set to null to use normal validation behavior.
    private static final Boolean SKIP_SHERPA_OVERRIDE = null;

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        // ── Validate Skip Sherpa API key, fall back to TruePeopleSearch if invalid ──
        System.out.println("Validating Skip Sherpa API key...");
        boolean useSkipSherpa = (SKIP_SHERPA_OVERRIDE != null)
                ? SKIP_SHERPA_OVERRIDE
                : SkipSherpaClient.validateApiKey();
        if (useSkipSherpa) {
            System.out.println("Skip Sherpa API key is valid — using Skip Sherpa.");
        } else {
            System.out.println("Skip Sherpa API key is invalid or unreachable — falling back to TruePeopleSearch scraper.");
        }

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

        System.out.println("Selected file: " + selectedPath);
        System.out.println("Output will be written to: " + outputPath);

        try (FileInputStream fis = new FileInputStream(inputPath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            // ── Find the sheet containing the data header ────────────────────
            // Some workbooks have multiple sheets (e.g. a cover page + data sheet).
            // Scan all sheets and use the first one with a recognizable header.
            Sheet sheet = null;
            Row headerRow = null;
            int nameCol = -1, cityCol = -1, stCol = -1, cityStZipCol = -1, fullAddressCol = -1, streetCol = -1;
            int phonesTpsCol = -1, emailsTpsCol = -1, notesCol = -1, contactCol = -1;
            // Track whether phonesTpsCol/emailsTpsCol are dedicated NEW output
            // columns (e.g. "New Numbers") vs pre-existing data columns ("Phone").
            // Resume skip only applies to dedicated output columns.
            boolean phonesColIsDedicated = false, emailsColIsDedicated = false;

            outer:
            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet candidateSheet = workbook.getSheetAt(sheetIdx);
                nameCol = -1; cityCol = -1; stCol = -1;
                cityStZipCol = -1; fullAddressCol = -1; streetCol = -1;
                phonesTpsCol = -1; emailsTpsCol = -1; notesCol = -1; contactCol = -1;
                phonesColIsDedicated = false; emailsColIsDedicated = false;
                headerRow = null;

                for (int r = 0; r <= candidateSheet.getLastRowNum(); r++) {
                    Row candidate = candidateSheet.getRow(r);
                    if (candidate == null) continue;

                    for (int c = 0; c < candidate.getLastCellNum(); c++) {
                        Cell cell = candidate.getCell(c);
                        if (cell == null) continue;
                        String header;
                        try {
                            if (cell.getCellType() == CellType.STRING) {
                                String raw = cell.getStringCellValue().trim();
                                if (raw.startsWith("=")) continue;
                                header = raw.toLowerCase();
                            } else if (cell.getCellType() == CellType.FORMULA) {
                                try {
                                    if (cell.getCachedFormulaResultType() == CellType.STRING) {
                                        header = cell.getStringCellValue().trim().toLowerCase();
                                    } else { continue; }
                                } catch (Exception ignored) { continue; }
                            } else { continue; }
                        } catch (Exception ignored) { continue; }

                        switch (header) {
                            case "name"                    -> nameCol        = c;
                            case "city"                    -> cityCol        = c;
                            case "st","state"              -> stCol          = c;
                            case "new numbers","phones"    -> { phonesTpsCol = c; phonesColIsDedicated = true; }
                            case "new emails"              -> { emailsTpsCol = c; emailsColIsDedicated = true; }
                            case "phone"   -> { if (phonesTpsCol == -1) phonesTpsCol = c; }
                            case "email","emails" -> { if (emailsTpsCol == -1) emailsTpsCol = c; }
                            case "address"                 -> fullAddressCol = c;
                            case "street"                  -> streetCol      = c;
                            case "notes","note"            -> notesCol       = c;
                            case "contact","owner"         -> contactCol     = c;
                            default -> {
                                if (header.startsWith("city") && (header.contains("st") || header.contains("zip"))) {
                                    cityStZipCol = c;
                                }
                            }
                        }
                    }

                    if (nameCol != -1 && ((cityCol != -1 && stCol != -1) || cityStZipCol != -1 || fullAddressCol != -1)) {
                        headerRow = candidate;
                        sheet = candidateSheet;
                        System.out.println("Found header row at row " + r + " on sheet '" + candidateSheet.getSheetName() + "'");
                        break outer;
                    }

                    // Reset for next row attempt within this sheet
                    nameCol = -1; cityCol = -1; stCol = -1;
                    cityStZipCol = -1; fullAddressCol = -1; streetCol = -1;
                    phonesTpsCol = -1; emailsTpsCol = -1; notesCol = -1; contactCol = -1;
                    phonesColIsDedicated = false; emailsColIsDedicated = false;
                }
            }

            // ── Cell style for Phone/Email cells — wrap text, bottom-aligned ──
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.BOTTOM);

            // ── Cache for red-font style variants, keyed by source style index.
            //    Reused across all rows so we don't blow past POI's 64k style
            //    ceiling on large sheets.
            java.util.Map<Short, CellStyle> redStyleCache = new java.util.HashMap<>();

            // ── Cache for bottom-aligned style variants. Same caching strategy
            //    as redStyleCache — avoids creating a new style per cell.
            java.util.Map<Short, CellStyle> alignStyleCache = new java.util.HashMap<>();

            if (sheet == null || headerRow == null) {
                JOptionPane.showMessageDialog(null,
                        "Could not find a header row with columns:\nName + City + St\nName + CITY,ST ZIP\nName + Address (full)\n\nSearched all " + workbook.getNumberOfSheets() + " sheet(s).",
                        "Missing Columns", JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            }

            int headerRowIndex = headerRow.getRowNum();
            int lastCol = headerRow.getLastCellNum();

            // Add output columns if they don't exist yet
            if (phonesTpsCol == -1) {
                phonesTpsCol = lastCol;
                headerRow.createCell(phonesTpsCol).setCellValue("Phone");
                lastCol++;
            }
            if (emailsTpsCol == -1) {
                emailsTpsCol = lastCol;
                headerRow.createCell(emailsTpsCol).setCellValue("Email");
                lastCol++;
            }
            if (notesCol == -1) {
                notesCol = lastCol;
                headerRow.createCell(notesCol).setCellValue("Notes");
                lastCol++;
            }
            if (contactCol == -1) {
                contactCol = lastCol;
                headerRow.createCell(contactCol).setCellValue("Contact");
                lastCol++;
            }

            // ── Process each data row (starting after the header) ────────────
            int totalRows = sheet.getLastRowNum();
            for (int r = headerRowIndex + 1; r <= totalRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String name = getCellString(row.getCell(nameCol));
                String city, state, street, zip;
                if (cityStZipCol != -1) {
                    // Dedicated "CITY, ST ZIP" combined column
                    String[] parsed = parseCityStateZip(getCellString(row.getCell(cityStZipCol)));
                    city   = parsed[0];
                    state  = parsed[1];
                    zip    = parsed[2];
                    street = (streetCol != -1) ? getCellString(row.getCell(streetCol)) : "";
                    // Use Address column as street if no dedicated street column
                    if (street.isEmpty() && fullAddressCol != -1) {
                        street = getCellString(row.getCell(fullAddressCol));
                        if (street.contains(",")) street = street.split(",")[0].trim();
                    }
                } else if (cityCol != -1 && stCol != -1) {
                    // Separate City and ST columns — most reliable, use directly
                    city   = getCellString(row.getCell(cityCol));
                    state  = getCellString(row.getCell(stCol));
                    zip    = "";
                    street = fullAddressCol != -1 ? getCellString(row.getCell(fullAddressCol))
                           : streetCol != -1 ? getCellString(row.getCell(streetCol)) : "";
                } else if (fullAddressCol != -1) {
                    // Single full address column — parse city/state from it
                    String rawAddr = getCellString(row.getCell(fullAddressCol));
                    String[] parsed = parseFullAddress(rawAddr);
                    city   = parsed[0];
                    state  = parsed[1];
                    zip    = "";
                    street = rawAddr.contains(",") ? rawAddr.split(",")[0].trim() : "";
                } else {
                    city   = "";
                    state  = "";
                    zip    = "";
                    street = "";
                }

                if (name.isEmpty() || city.isEmpty() || state.isEmpty()) {
                    System.out.println("Skipping row " + r + " (missing data)");
                    continue;
                }

                // ── Skip rows already completed in a previous run ─────────────
                // Only skip if the cell contains a properly formatted phone number
                // (our output format) or a valid email address. This prevents
                // pre-existing data like "AT&T" or "Verizon Wireless" from
                // being mistaken for a completed row.
                // Only skip if a DEDICATED output column (e.g. "New Numbers") already
                // has data from a previous run. Never skip based on pre-existing
                // "Phone"/"Email" columns that had data before the program ran.
                String existingPhone = phonesColIsDedicated ? getCellString(row.getCell(phonesTpsCol)) : "";
                String existingEmail = emailsColIsDedicated ? getCellString(row.getCell(emailsTpsCol)) : "";
                boolean hasOurPhone = existingPhone.matches("(?s).*\\(\\d{3}\\)\\s\\d{3}-\\d{4}.*");
                boolean hasOurEmail = existingEmail.matches("(?s).*[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}.*");
                if (hasOurPhone || hasOurEmail) {
                    System.out.println("Skipping row " + r + " (already completed): " + name);
                    continue;
                }

                System.out.println("Searching for: " + name + ", " + city + ", " + state);

                // Search using Skip Sherpa API or TruePeopleSearch depending on key validity
                List<String> foundPhones = new java.util.ArrayList<>();
                List<String> foundEmails = new java.util.ArrayList<>();
                List<String> foundAssociatedPersons = new java.util.ArrayList<>();
                // True when Skip Sherpa reports the person/business is deceased or
                // returned a non-200 (404 not in DB, 5xx server error). Used to
                // color the entire row red at the end of the iteration.
                boolean shouldColorRowRed = false;

                if (useSkipSherpa) {
                    // ── Skip Sherpa API — route to person or business endpoint ─
                    boolean isJT  = SkipSherpaClient.isJointTenancy(name);
                    boolean isBiz = !isJT && SkipSherpaClient.isBusiness(name);

                    // For trust/estate/heirs names, try to extract a real person to search
                    String trustPersonName = null;
                    if (isJT) {
                        // Joint tenancy — search the first named person
                        trustPersonName = SkipSherpaClient.extractFirstPersonFromJT(name);
                    } else if (isBiz || name.matches("(?si).*\\bEstate\\s+of\\b.*")
                              || name.matches("(?si).*Known and Unknown.*")) {
                        trustPersonName = SkipSherpaClient.extractPersonFromTrust(name);
                    }

                    System.out.println("Looking up via Skip Sherpa API ("
                            + (isJT ? "Person (Joint Tenancy)"
                               : trustPersonName != null ? "Person (from Trust/Estate)"
                               : isBiz ? "Business" : "Person") + ")...");

                    SkipSherpaClient.PersonContact contact = null;
                    for (int attempt = 1; attempt <= 2 && contact == null; attempt++) {
                        try {
                            if (trustPersonName != null) {
                                // Trust/estate or JT: search the extracted person name
                                contact = SkipSherpaClient.search(trustPersonName, street, city, state, zip);
                            } else if (isBiz) {
                                contact = SkipSherpaClient.searchBusiness(name, street, city, state, zip);
                            } else {
                                contact = SkipSherpaClient.search(name, street, city, state, zip);
                            }
                        } catch (SkipSherpaClient.RateLimitException rle) {
                            // ── Quota exhausted — save progress and exit immediately ──
                            System.out.println("Skip Sherpa quota exhausted. Saving progress and exiting.");
                            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                                workbook.write(fos);
                            }
                            System.out.println("Progress saved. Re-run after your quota refills.");
                            JOptionPane.showMessageDialog(null,
                                    "Skip Sherpa lookup quota exhausted (HTTP 429).\n\n"
                                    + "Progress saved to:\n" + outputPath
                                    + "\n\nRe-run after your monthly allocation refills\n"
                                    + "or purchase additional lookups.",
                                    "Stopped — Quota Exhausted", JOptionPane.WARNING_MESSAGE);
                            return;
                        } catch (Exception ex) {
                            String msg = ex.getMessage() != null ? ex.getMessage() : "unknown error";
                            System.out.println("  API error (attempt " + attempt + "): " + msg);
                            if (attempt < 2) Thread.sleep(5000);
                            else contact = new SkipSherpaClient.PersonContact();
                        }
                    }
                    if (contact != null) {
                        foundPhones = contact.phones;
                        foundEmails = contact.emails;
                        // Capture associated persons for business lookups
                        if (isBiz && !contact.associatedPersons.isEmpty()) {
                            foundAssociatedPersons = contact.associatedPersons;
                        }

                        // Color the row red on any non-200 from Skip Sherpa
                        // (404 = not in database, 5xx = server error).
                        if (contact.notFound) {
                            shouldColorRowRed = true;
                        }

                        // ── Deceased handling ─────────────────────────────────
                        if (contact.deceased) {
                            shouldColorRowRed = true;
                            // Append (DECEASED) to the first line of the name cell only,
                            // preserving any c/o or other lines that follow.
                            Cell nameCell = getOrCreateCell(row, nameCol);
                            String currentName = nameCell.getStringCellValue();
                            if (!currentName.contains("(DECEASED)")) {
                                int newlineIdx = currentName.indexOf('\n');
                                if (newlineIdx >= 0) {
                                    // Multi-line: tag the first line, keep the rest
                                    nameCell.setCellValue(
                                        currentName.substring(0, newlineIdx).trim()
                                        + " (DECEASED)"
                                        + currentName.substring(newlineIdx));
                                } else {
                                    nameCell.setCellValue(currentName.trim() + " (DECEASED)");
                                }
                            }

                            // Build relative names list for the Notes cell
                            if (!contact.relatives.isEmpty()) {
                                List<String> relativeNames = new ArrayList<>();
                                for (SkipSherpaClient.Relative rel : contact.relatives) {
                                    relativeNames.add(rel.name);
                                }
                                String relNamesStr = "Relatives: " + String.join(", ", relativeNames);

                                // Append to existing Notes content (don't overwrite)
                                Cell notesCell = getOrCreateCell(row, notesCol);
                                String existingNotes = notesCell.getCellType() == CellType.STRING
                                        ? notesCell.getStringCellValue().trim() : "";
                                String newNotes = existingNotes.isEmpty()
                                        ? relNamesStr
                                        : existingNotes + "\n" + relNamesStr;
                                // Only write if relatives line isn't already there
                                if (!existingNotes.contains("Relatives:")) {
                                    notesCell.setCellValue(newNotes);
                                    notesCell.setCellStyle(wrapStyle);
                                }

                                // Also append relative contact data to phones/emails
                                StringBuilder relPhones = new StringBuilder();
                                StringBuilder relEmails = new StringBuilder();
                                for (SkipSherpaClient.Relative rel : contact.relatives) {
                                    if (rel.phones.isEmpty() && rel.emails.isEmpty()) continue;
                                    String hdr = "── " + rel.name
                                            + (rel.address.isEmpty() ? "" : " (" + rel.address + ")") + " ──";
                                    if (!rel.phones.isEmpty()) {
                                        if (relPhones.length() > 0) relPhones.append("\n");
                                        relPhones.append(hdr).append("\n")
                                                 .append(String.join("\n", rel.phones));
                                    }
                                    if (!rel.emails.isEmpty()) {
                                        if (relEmails.length() > 0) relEmails.append("\n");
                                        relEmails.append(hdr).append("\n")
                                                 .append(String.join("\n", rel.emails));
                                    }
                                }
                                if (relPhones.length() > 0) {
                                    foundPhones.add("── DECEASED — RELATIVES ──");
                                    foundPhones.addAll(java.util.Arrays.asList(
                                            relPhones.toString().split("\n")));
                                }
                                if (relEmails.length() > 0) {
                                    foundEmails.add("── DECEASED — RELATIVES ──");
                                    foundEmails.addAll(java.util.Arrays.asList(
                                            relEmails.toString().split("\n")));
                                }
                            }
                        }
                    }
                } else {
                    // ── TruePeopleSearch scraper ──────────────────────────────
                    System.out.println("Looking up via TruePeopleSearch...");
                    TruePeopleSearchScraper.PersonContact contact = null;
                    int attempts = 0;
                    boolean ipBanned = false;
                    while (contact == null && attempts < 2) {
                        attempts++;
                        try {
                            contact = TruePeopleSearchScraper.search(name, street, city, state, zip);
                        } catch (TruePeopleSearchScraper.IpBanException banEx) {
                            System.out.println("IP ban could not be resolved. Saving progress and exiting.");
                            ipBanned = true;
                            break;
                        } catch (RuntimeException browserEx) {
                            String msg = browserEx.getMessage() != null ? browserEx.getMessage().split("\n")[0] : "unknown error";
                            System.out.println("Browser/subprocess error (attempt " + attempts + "): " + msg);
                            if (attempts < 2) {
                                System.out.println("Restarting scraper and retrying row...");
                                TruePeopleSearchScraper.restartDriver();
                                Thread.sleep(3000);
                            } else {
                                contact = new TruePeopleSearchScraper.PersonContact();
                            }
                        }
                    }
                    if (ipBanned) {
                        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                            workbook.write(fos);
                        }
                        TruePeopleSearchScraper.quit();
                        System.out.println("Progress saved. Re-run after the IP ban lifts.");
                        JOptionPane.showMessageDialog(null,
                                "IP ban could not be resolved.\n\nProgress saved to:\n" + outputPath
                                + "\n\nRe-run after the ban lifts to collect remaining rows.",
                                "Stopped — IP Banned", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    if (contact != null) {
                        foundPhones = contact.phones;
                        foundEmails = contact.emails;
                    }
                }

                String phonesStr = String.join("\n", foundPhones);
                String emailsStr = String.join("\n", foundEmails);

                Cell phoneCell = getOrCreateCell(row, phonesTpsCol);
                phoneCell.setCellValue(phonesStr);
                phoneCell.setCellStyle(wrapStyle);

                Cell emailCell = getOrCreateCell(row, emailsTpsCol);
                emailCell.setCellValue(emailsStr);
                emailCell.setCellStyle(wrapStyle);

                // ── Write associated persons (business owner/officer) to Contact column ──
                if (!foundAssociatedPersons.isEmpty()) {
                    Cell contactCell = getOrCreateCell(row, contactCol);
                    String existingContact = contactCell.getCellType() == CellType.STRING
                            ? contactCell.getStringCellValue().trim() : "";
                    // Only write if the cell doesn't already have contact data
                    if (existingContact.isEmpty()) {
                        String contactStr = String.join("", foundAssociatedPersons);
                        contactCell.setCellValue(contactStr);
                        contactCell.setCellStyle(wrapStyle);
                        System.out.println("Associated persons: " + foundAssociatedPersons);
                    }
                }

                System.out.println("Found phones: " + foundPhones);
                System.out.println("Found emails: " + foundEmails);

                // ── Normalize vertical alignment to BOTTOM for every cell in the
                //    row. Pre-existing cells (Name, Address, etc.) may have CENTER
                //    alignment from the original spreadsheet; our output cells use
                //    wrapStyle which is already BOTTOM. This pass catches the
                //    pre-existing cells so the whole row is consistent.
                normalizeRowAlignment(row, lastCol, workbook, alignStyleCache);

                // ── Color the entire row red if deceased or SS returned non-200 ──
                if (shouldColorRowRed) {
                    colorRowRed(row, lastCol, workbook, redStyleCache);
                    System.out.println("  Row colored red (deceased or not found in Skip Sherpa).");
                }

                // Save progress to disk after every row so a crash doesn't lose work
                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    workbook.write(fos);
                }

                // TPS needs a delay between requests to avoid IP bans.
                // Skip Sherpa is a direct API — no delay needed.
                if (!useSkipSherpa) {
                    int jitter = (int)(Math.random() * 30) - 15;
                    int actualDelay = Math.max(20, 45 + jitter);
                    System.out.println("Sleeping for " + actualDelay + " seconds...");
                    Thread.sleep(actualDelay * 1000L);
                }
                System.out.println("--------------------");
            }

            // ── Auto-size Phone and Email columns before final save ──────────
            // Use a fixed width wide enough for a phone number / email address.
            // autoSizeColumn() is slow and unreliable with wrapped multi-line cells,
            // so we set a sensible fixed width instead (units are 1/256th of a char).
            sheet.setColumnWidth(phonesTpsCol, 18 * 256);  // ~18 chars = (405) 947-4673
            sheet.setColumnWidth(emailsTpsCol, 35 * 256);  // ~35 chars for a typical email

            // ── Write output ─────────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.write(fos);
            }

            if (!useSkipSherpa) TruePeopleSearchScraper.quit();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("Results written to: " + outputPath);
            System.out.println("Total run time: " + formatElapsed(elapsed));


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
            try { TruePeopleSearchScraper.quit(); } catch (Exception ignored) {}
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
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                // Return cached result of formula
                try {
                    yield switch (cell.getCachedFormulaResultType()) {
                        case STRING  -> cell.getStringCellValue().trim();
                        case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                        default -> "";
                    };
                } catch (Exception e) { yield ""; }
            }
            default -> "";
        };
    }

    /** Get or create a cell at the given column index. */
    private static Cell getOrCreateCell(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        return cell;
    }

    /**
     * Apply red font color to every cell in the given row (from column 0 through
     * lastCol - 1), preserving each cell's existing style (fill, borders,
     * alignment, wrap, etc.) except for font color.
     *
     * Styles are cached by source-style index so we don't create a new style
     * object per cell — XSSF has a 64k style limit and creating a style per
     * cell would bloat the file and risk hitting that ceiling on large sheets.
     *
     * @param row       the row to recolor
     * @param lastCol   exclusive upper bound of columns to iterate
     * @param wb        workbook (needed to create styles and fonts)
     * @param styleCache map from source style index to red-font variant;
     *                   the caller should reuse the same map across all rows
     *                   within a single run
     */
    private static void colorRowRed(Row row, int lastCol, Workbook wb,
                                    java.util.Map<Short, CellStyle> styleCache) {
        // One red font per workbook, reused across all styles
        Font redFont = null;
        for (int c = 0; c < lastCol; c++) {
            Cell cell = getOrCreateCell(row, c);
            CellStyle src = cell.getCellStyle();
            short srcIdx = src == null ? -1 : src.getIndex();
            CellStyle redVariant = styleCache.get(srcIdx);
            if (redVariant == null) {
                if (redFont == null) {
                    redFont = wb.createFont();
                    redFont.setColor(IndexedColors.RED.getIndex());
                }
                redVariant = wb.createCellStyle();
                if (src != null) redVariant.cloneStyleFrom(src);
                redVariant.setFont(redFont);
                redVariant.setVerticalAlignment(VerticalAlignment.BOTTOM);
                styleCache.put(srcIdx, redVariant);
            }
            cell.setCellStyle(redVariant);
        }
    }

    /**
     * Normalize every cell in the row to BOTTOM vertical alignment.
     * Pre-existing cells from the original spreadsheet may have CENTER or
     * no explicit alignment (which Excel renders as BOTTOM anyway, but
     * CENTER is visually wrong when our output cells are BOTTOM).
     *
     * Uses the same caching strategy as {@link #colorRowRed} to avoid
     * creating a new CellStyle per cell.
     */
    private static void normalizeRowAlignment(Row row, int lastCol, Workbook wb,
                                              java.util.Map<Short, CellStyle> styleCache) {
        for (int c = 0; c < lastCol; c++) {
            Cell cell = getOrCreateCell(row, c);
            CellStyle src = cell.getCellStyle();
            if (src != null && src.getVerticalAlignment() == VerticalAlignment.BOTTOM) {
                continue; // already correct
            }
            short srcIdx = src == null ? -1 : src.getIndex();
            CellStyle bottomVariant = styleCache.get(srcIdx);
            if (bottomVariant == null) {
                bottomVariant = wb.createCellStyle();
                if (src != null) bottomVariant.cloneStyleFrom(src);
                bottomVariant.setVerticalAlignment(VerticalAlignment.BOTTOM);
                styleCache.put(srcIdx, bottomVariant);
            }
            cell.setCellStyle(bottomVariant);
        }
    }

    /**
     * Parse city and state from a full address like:
     *   "8101 NW 110th St, Oklahoma City, OK 73107" → ["Oklahoma City", "OK"]
     *   "123 Main St, Edmond, OK"                   → ["Edmond", "OK"]
     *
     * Strategy: split on commas, take the last two segments.
     * The second-to-last segment is the city; the last segment starts with the state code.
     * If fewer than 3 comma-separated parts, fall back to parseCityStateZip.
     */
    private static String[] parseFullAddress(String raw) {
        if (raw == null || raw.isBlank()) return new String[]{"", ""};
        // Normalize whitespace and newlines
        raw = raw.replaceAll("[\r\n]+", " ").replaceAll("\\s{2,}", " ").trim();
        // Strip trailing commas and country suffixes
        raw = raw.replaceAll(",\\s*$", "").trim();
        raw = raw.replaceAll("(?i),?\\s*(United States|USA|US)\\s*$", "").trim();
        raw = raw.replaceAll(",\\s*$", "").trim();
        // Normalize period-as-separator: "321 S Boston. Tulsa" → "321 S Boston, Tulsa"
        raw = raw.replaceAll("\\.\\s+(?=[A-Z])", ", ");

        String[] parts = raw.split(",");
        for (int i = parts.length - 1; i >= 1; i--) {
            String seg = parts[i].trim();
            // Strip trailing ZIP to isolate state token: "OK 73102" → "OK"
            String stToken = seg.replaceAll("\\s+\\d{5}(-\\d{4})?$", "").trim();
            String state = resolveStateToken(stToken);
            if (state != null) {
                for (int j = i - 1; j >= 0; j--) {
                    String part = parts[j].trim();
                    if (part.matches("\\d{5}(-\\d{4})?")) continue; // skip bare ZIP
                    String city = extractCityFromSegment(part);
                    if (!city.isEmpty()) return new String[]{city, state};
                }
            }
        }

        // Fallback: "City ST 12345" with no comma before state code
        java.util.regex.Matcher fb = java.util.regex.Pattern.compile(
                ",\\s*([A-Za-z][A-Za-z ]+?)\\s+([A-Z]{2})\\s+\\d{5}").matcher(raw);
        if (fb.find()) return new String[]{fb.group(1).trim(), fb.group(2).trim()};

        return new String[]{"", ""};
    }

    /** Resolve a token to a 2-letter US state code, or null if not recognized. */
    private static String resolveStateToken(String token) {
        token = token.trim();
        if (token.matches("[A-Z]{2}")) return token;
        if (token.matches("[A-Za-z]{2}")) return token.toUpperCase();
        String[][] states = {
            {"Alabama","AL"},{"Alaska","AK"},{"Arizona","AZ"},{"Arkansas","AR"},
            {"California","CA"},{"Colorado","CO"},{"Connecticut","CT"},{"Delaware","DE"},
            {"Florida","FL"},{"Georgia","GA"},{"Hawaii","HI"},{"Idaho","ID"},
            {"Illinois","IL"},{"Indiana","IN"},{"Iowa","IA"},{"Kansas","KS"},
            {"Kentucky","KY"},{"Louisiana","LA"},{"Maine","ME"},{"Maryland","MD"},
            {"Massachusetts","MA"},{"Michigan","MI"},{"Minnesota","MN"},{"Mississippi","MS"},
            {"Missouri","MO"},{"Montana","MT"},{"Nebraska","NE"},{"Nevada","NV"},
            {"New Hampshire","NH"},{"New Jersey","NJ"},{"New Mexico","NM"},{"New York","NY"},
            {"North Carolina","NC"},{"North Dakota","ND"},{"Ohio","OH"},{"Oklahoma","OK"},
            {"Oregon","OR"},{"Pennsylvania","PA"},{"Rhode Island","RI"},{"South Carolina","SC"},
            {"South Dakota","SD"},{"Tennessee","TN"},{"Texas","TX"},{"Utah","UT"},
            {"Vermont","VT"},{"Virginia","VA"},{"Washington","WA"},{"West Virginia","WV"},
            {"Wisconsin","WI"},{"Wyoming","WY"}
        };
        for (String[] s : states) {
            if (s[0].equalsIgnoreCase(token)) return s[1];
        }
        return null;
    }

    /**
     * Extract a city name from an address segment, stripping PO Box prefixes
     * and suite numbers. E.g. "PO BOX 51138. Midland" → "Midland".
     */
    private static String extractCityFromSegment(String seg) {
        String s = seg.trim();
        // Strip PO Box / Drawer prefix
        s = s.replaceAll("(?i)^p\\.?\\s*o\\.?\\s*box\\s+\\S+\\s*,?\\s*", "");
        s = s.replaceAll("(?i)^p\\.?\\s*o\\.?\\s+drawer\\s+\\S+\\s*,?\\s*", "");
        s = s.trim();
        // If what remains still has a leading address (e.g. "105 N HUDSON AVE STE 800 Oklahoma City"),
        // grab the last run of capitalized words — handles Mc/Mac prefixes and mixed case
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Z][A-Za-z]+(?:\\s[A-Z][A-Za-z]+)*)$")
                .matcher(s);
        if (m.find()) return m.group(1).trim();
        return s;
    }
    /**
     * Parse a combined "City, ST ZIP" or "City, ST" cell into [city, state].
     * Examples:
     *   "Evergreen, CO 80439"  → ["Evergreen", "CO"]
     *   "Oklahoma City, OK"    → ["Oklahoma City", "OK"]
     *   "Custer City, OK 73639" → ["Custer City", "OK"]
     */
    private static String[] parseCityStateZip(String raw) {
        if (raw == null || raw.isBlank()) return new String[]{"", "", ""};
        // Split on the last comma — everything before is the city,
        // everything after is "ST" or "ST ZIP"
        int lastComma = raw.lastIndexOf(',');
        if (lastComma < 0) return new String[]{raw.trim(), "", ""};
        String city  = raw.substring(0, lastComma).trim();
        String stZip = raw.substring(lastComma + 1).trim();
        // State is the first token; ZIP (if present) is the second
        String[] tokens = stZip.split("\\s+");
        String state = tokens[0].trim();
        String zip   = tokens.length > 1 ? tokens[1].trim() : "";
        return new String[]{city, state, zip};
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