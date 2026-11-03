import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/**
 * Creates the sample HRM documents of the fictional company "Northwind Ltd".
 *
 *   mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
 *   java -cp "target/classes;<classpath>" scripts/GenerateSampleDocs.java        (see scripts/generate-sample-docs.cmd)
 *
 * What it writes into sample-docs/:
 *   - 8 policy documents (4 PDF, 4 DOCX). Their text is in scripts/sample-content/*.txt: edit the text there.
 *   - the Complete Employee Handbook: ONE PDF of exactly 500 pages (the maximum the application accepts).
 *     It contains the 8 policies as chapters, followed by generated annexes (office calendars, grade tables,
 *     a forms catalogue, department rules, FAQ, glossary and course datasheets). The annexes are made from
 *     tables and templates, so their facts are invented but consistent. They exist to give the search a large,
 *     realistic, fact-rich document.
 *
 * ALL PEOPLE, PLACES, PHONE NUMBERS AND E-MAIL ADDRESSES ARE FICTIONAL (555 numbers, .example addresses).
 *
 * The text-markup of the content files:   "# " title   "## " section   "### " subsection   "- " list item
 *                                          "| a | b |" table row (the first row is the header)   blank line = new paragraph
 */
public class GenerateSampleDocs {

    static final int HANDBOOK_PAGES = 500;
    static final String COMPANY = "Northwind Ltd";

    public static void main(String[] args) throws IOException {
        Path content = Path.of("scripts", "sample-content");
        Path out = Path.of(args.length > 0 ? args[0] : "sample-docs");
        Files.createDirectories(out);

        List<Policy> policies = List.of(
                policy(content, "01-leave-policy", true),
                policy(content, "02-code-of-conduct-and-workplace-rules", false),
                policy(content, "03-work-life-balance-guidelines", false),
                policy(content, "04-employee-benefits-guide", true),
                policy(content, "05-remote-and-hybrid-work-policy", true),
                policy(content, "06-it-security-and-data-privacy-policy", false),
                policy(content, "07-health-safety-and-business-travel-policy", true),
                policy(content, "08-recruitment-performance-and-exit-policy", false));

        for (Policy policy : policies) {
            Path file = out.resolve(policy.fileBase + (policy.pdf ? ".pdf" : ".docx"));
            if (policy.pdf) {
                PdfBuilder pdf = new PdfBuilder(policy.title);
                pdf.newPage();
                pdf.render(policy.blocks, false);
                int pages = pdf.save(file, policy.title);
                System.out.println(file.getFileName() + "  " + pages + " pages");
            } else {
                writeDocx(file, policy);
                System.out.println(file.getFileName() + "  (DOCX)");
            }
        }

        Path handbook = out.resolve("northwind-complete-employee-handbook-2026.pdf");
        int pages = writeHandbook(handbook, policies);
        System.out.println(handbook.getFileName() + "  " + pages + " pages");
    }

    // ===================================================================
    // Content model and markup parser
    // ===================================================================

    sealed interface Block permits Heading, Para, Bullets, Table {
    }

    record Heading(int level, String text) implements Block {
    }

    record Para(String text) implements Block {
    }

    record Bullets(List<String> items) implements Block {
    }

    record Table(List<List<String>> rows) implements Block {
    }

    record Policy(String fileBase, String title, boolean pdf, List<Block> blocks) {
    }

    static Policy policy(Path contentDir, String fileBase, boolean pdf) throws IOException {
        List<Block> blocks = parse(Files.readString(contentDir.resolve(fileBase + ".txt")));
        String title = ((Heading) blocks.get(0)).text();
        return new Policy(fileBase, title, pdf, blocks);
    }

    static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        List<String> bullets = new ArrayList<>();
        List<List<String>> table = new ArrayList<>();

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            boolean isBullet = line.startsWith("- ");
            boolean isTableRow = line.startsWith("|");
            if (!isBullet && !bullets.isEmpty()) {
                blocks.add(new Bullets(List.copyOf(bullets)));
                bullets.clear();
            }
            if (!isTableRow && !table.isEmpty()) {
                blocks.add(new Table(List.copyOf(table)));
                table.clear();
            }
            if (line.isEmpty() || line.startsWith("#") || isBullet || isTableRow) {
                flush(blocks, paragraph);
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("### ")) {
                blocks.add(new Heading(3, line.substring(4)));
            } else if (line.startsWith("## ")) {
                blocks.add(new Heading(2, line.substring(3)));
            } else if (line.startsWith("# ")) {
                blocks.add(new Heading(1, line.substring(2)));
            } else if (isBullet) {
                bullets.add(line.substring(2));
            } else if (isTableRow) {
                List<String> cells = new ArrayList<>();
                for (String cell : line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length()).split("\\|")) {
                    cells.add(cell.strip());
                }
                table.add(cells);
            } else {
                paragraph.append(paragraph.isEmpty() ? "" : " ").append(line);
            }
        }
        flush(blocks, paragraph);
        if (!bullets.isEmpty()) {
            blocks.add(new Bullets(List.copyOf(bullets)));
        }
        if (!table.isEmpty()) {
            blocks.add(new Table(List.copyOf(table)));
        }
        return blocks;
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Para(paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    // ===================================================================
    // PDF
    // ===================================================================

    /** Writes text into A4 pages with headings, lists, tables, a footer with the page number and bookmarks. */
    static class PdfBuilder {
        static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
        static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
        static final float MARGIN = 56;
        static final float TOP = PAGE_HEIGHT - 62;
        static final float BOTTOM = 72;
        static final float TEXT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
        static final float BODY = 10.5f;
        static final float LEADING = 14.5f;

        final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final PDType1Font italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

        final PDDocument document = new PDDocument();
        final String footerTitle;
        final List<Entry> entries = new ArrayList<>();   // headings, for bookmarks and the table of contents
        PDPage page;
        PDPageContentStream stream;
        float y;
        int pageNumber;

        /** A heading with the page it is on. */
        record Entry(int level, String text, int pageNumber, PDPage page) {
        }

        PdfBuilder(String footerTitle) {
            this.footerTitle = footerTitle;
        }

        int pageCount() {
            return pageNumber;
        }

        void newPage() {
            try {
                closePage();
                page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                stream = new PDPageContentStream(document, page);
                pageNumber++;
                y = TOP;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void closePage() throws IOException {
            if (stream != null) {
                drawFooter(stream, pageNumber);
                stream.close();
                stream = null;
            }
        }

        private void drawFooter(PDPageContentStream cs, int number) throws IOException {
            cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
            cs.setStrokingColor(0.8f, 0.8f, 0.8f);
            cs.moveTo(MARGIN, 52);
            cs.lineTo(PAGE_WIDTH - MARGIN, 52);
            cs.stroke();
            write(cs, COMPANY + " - " + footerTitle, regular, 8, MARGIN, 40);
            String pageText = "Page " + number;
            write(cs, pageText, regular, 8, PAGE_WIDTH - MARGIN - width(pageText, regular, 8), 40);
            cs.setNonStrokingColor(0f, 0f, 0f);
        }

        // ----- rendering of blocks -----

        void render(List<Block> blocks, boolean newPageForEveryTitle) {
            boolean first = true;
            for (Block block : blocks) {
                switch (block) {
                    case Heading heading -> {
                        if (heading.level() == 1 && newPageForEveryTitle && !first) {
                            newPage();
                        }
                        heading(heading.level(), heading.text());
                    }
                    case Para para -> paragraph(para.text(), first ? italic : regular);
                    case Bullets bullets -> bullets(bullets.items());
                    case Table table -> table(table.rows());
                }
                first = false;
            }
        }

        void heading(int level, String text) {
            float[] sizes = {0, 20, 14, 11.5f};
            float size = sizes[level];
            float before = level == 1 ? 6 : level == 2 ? 14 : 8;
            ensureSpace(before + size * 1.4f + 3 * LEADING);   // never leave a heading alone at the bottom of a page
            y -= before;
            entries.add(new Entry(level, text, pageNumber, page));
            for (String line : wrap(text, bold, size, TEXT_WIDTH)) {
                write(stream, line, bold, size, MARGIN, y - size);
                y -= size * 1.35f;
            }
            y -= level == 1 ? 8 : 3;
        }

        void paragraph(String text, PDFont font) {
            paragraph(text, font, MARGIN, TEXT_WIDTH, BODY);
        }

        void paragraph(String text, PDFont font, float x, float width, float size) {
            for (String line : wrap(text, font, size, width)) {
                ensureSpace(LEADING);
                write(stream, line, font, size, x, y - size);
                y -= LEADING;
            }
            y -= 5;
        }

        void bullets(List<String> items) {
            for (String item : items) {
                List<String> lines = wrap(item, regular, BODY, TEXT_WIDTH - 18);
                ensureSpace(LEADING);
                write(stream, "-", regular, BODY, MARGIN + 4, y - BODY);
                for (String line : lines) {
                    ensureSpace(LEADING);
                    write(stream, line, regular, BODY, MARGIN + 18, y - BODY);
                    y -= LEADING;
                }
                y -= 2;
            }
            y -= 4;
        }

        void table(List<List<String>> rows) {
            int columns = rows.stream().mapToInt(List::size).max().orElse(1);
            float[] widths = new float[columns];
            for (int c = 0; c < columns; c++) {
                widths[c] = columns == 2 ? (c == 0 ? 0.40f : 0.60f) * TEXT_WIDTH : TEXT_WIDTH / columns;
            }
            for (int r = 0; r < rows.size(); r++) {
                boolean header = r == 0;
                PDFont font = header ? bold : regular;
                List<List<String>> cells = new ArrayList<>();
                int lines = 1;
                for (int c = 0; c < columns; c++) {
                    String text = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                    List<String> wrapped = wrap(text, font, 9.5f, widths[c] - 10);
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                float height = lines * 12.5f + 7;
                ensureSpace(height + (header ? 2 * 12.5f : 0));
                try {
                    if (header) {
                        stream.setNonStrokingColor(0.90f, 0.92f, 0.96f);
                        stream.addRect(MARGIN, y - height, TEXT_WIDTH, height);
                        stream.fill();
                        stream.setNonStrokingColor(0f, 0f, 0f);
                    }
                    stream.setStrokingColor(0.75f, 0.75f, 0.75f);
                    stream.moveTo(MARGIN, y - height);
                    stream.lineTo(MARGIN + TEXT_WIDTH, y - height);
                    stream.stroke();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                float x = MARGIN;
                for (int c = 0; c < columns; c++) {
                    float lineY = y - 4;
                    for (String line : cells.get(c)) {
                        write(stream, line, font, 9.5f, x + 5, lineY - 9.5f);
                        lineY -= 12.5f;
                    }
                    x += widths[c];
                }
                y -= height;
            }
            y -= 10;
        }

        void spacer(float points) {
            y -= points;
        }

        void ensureSpace(float needed) {
            if (y - needed < BOTTOM) {
                newPage();
            }
        }

        // ----- text helpers -----

        List<String> wrap(String text, PDFont font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : clean(text).split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && width(candidate, font, size) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        float width(String text, PDFont font, float size) {
            try {
                return font.getStringWidth(clean(text)) / 1000f * size;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void write(PDPageContentStream cs, String text, PDFont font, float size, float x, float baseline) {
            try {
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(x, baseline);
                cs.showText(clean(text));
                cs.endText();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The standard PDF fonts only know Latin-1 characters. */
        static String clean(String text) {
            StringBuilder out = new StringBuilder();
            for (char c : text.toCharArray()) {
                out.append(c >= 32 && c <= 255 ? c : ' ');
            }
            return out.toString();
        }

        // ----- finishing -----

        /** Closes the last page, adds bookmarks for the headings of level 1, and saves. Returns the number of pages. */
        int save(Path file, String title) {
            try {
                closePage();
                PDDocumentOutline outline = new PDDocumentOutline();
                document.getDocumentCatalog().setDocumentOutline(outline);
                for (Entry entry : entries) {
                    if (entry.level() == 1) {
                        PDOutlineItem item = new PDOutlineItem();
                        item.setTitle(clean(entry.text()));
                        PDPageFitDestination destination = new PDPageFitDestination();
                        destination.setPage(entry.page());
                        item.setDestination(destination);
                        outline.addLast(item);
                    }
                }
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle(title);
                info.setAuthor(COMPANY + " - People and Culture (fictional sample document)");
                document.setDocumentInformation(info);
                document.save(file.toFile());
                int pages = document.getNumberOfPages();
                document.close();
                return pages;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Writes the table of contents onto pages that were reserved earlier. */
        void drawTableOfContents(int firstReservedPage, int reservedPages, List<Entry> toc) throws IOException {
            int pageIndex = firstReservedPage - 1;
            PDPageContentStream cs = new PDPageContentStream(document, document.getPage(pageIndex),
                    PDPageContentStream.AppendMode.APPEND, true, true);
            float line = TOP - 40;
            write(cs, "Contents", bold, 20, MARGIN, TOP - 20);
            int usedPages = 1;
            for (Entry entry : toc) {
                if (line < BOTTOM) {
                    cs.close();
                    if (usedPages >= reservedPages) {
                        throw new IllegalStateException("The table of contents needs more than " + reservedPages + " pages");
                    }
                    cs = new PDPageContentStream(document, document.getPage(pageIndex + usedPages),
                            PDPageContentStream.AppendMode.APPEND, true, true);
                    usedPages++;
                    line = TOP - 10;
                }
                PDFont font = entry.level() == 1 ? bold : regular;
                float size = entry.level() == 1 ? 10.5f : 9.5f;
                float indent = entry.level() == 1 ? 0 : 14;
                String number = String.valueOf(entry.pageNumber());
                float numberWidth = width(number, font, size);
                float textWidth = width(entry.text(), font, size);
                write(cs, entry.text(), font, size, MARGIN + indent, line);
                write(cs, number, font, size, PAGE_WIDTH - MARGIN - numberWidth, line);
                float dotWidth = width(".", regular, size);
                int dots = (int) ((TEXT_WIDTH - indent - textWidth - numberWidth - 12) / dotWidth);
                if (dots > 0) {
                    write(cs, ".".repeat(dots), regular, size, MARGIN + indent + textWidth + 6, line);
                }
                line -= entry.level() == 1 ? 17 : 13;
            }
            cs.close();
        }
    }

    // ===================================================================
    // DOCX
    // ===================================================================

    static void writeDocx(Path file, Policy policy) throws IOException {
        try (XWPFDocument docx = new XWPFDocument()) {
            XWPFStyles styles = docx.createStyles();
            addHeadingStyle(styles, "Heading1", "heading 1", 0, 20);
            addHeadingStyle(styles, "Heading2", "heading 2", 1, 14);
            addHeadingStyle(styles, "Heading3", "heading 3", 2, 12);

            boolean first = true;
            for (Block block : policy.blocks) {
                switch (block) {
                    case Heading heading -> {
                        XWPFParagraph p = docx.createParagraph();
                        p.setStyle("Heading" + heading.level());
                        p.createRun().setText(heading.text());
                    }
                    case Para para -> {
                        XWPFParagraph p = docx.createParagraph();
                        p.setSpacingAfter(120);
                        XWPFRun run = p.createRun();
                        run.setText(para.text());
                        run.setItalic(first);
                    }
                    case Bullets bullets -> {
                        for (String item : bullets.items()) {
                            XWPFParagraph p = docx.createParagraph();
                            p.setIndentationLeft(360);
                            p.createRun().setText("• " + item);
                        }
                    }
                    case Table table -> {
                        int columns = table.rows().stream().mapToInt(List::size).max().orElse(1);
                        XWPFTable t = docx.createTable(table.rows().size(), columns);
                        for (int r = 0; r < table.rows().size(); r++) {
                            XWPFTableRow row = t.getRow(r);
                            for (int c = 0; c < columns; c++) {
                                String text = c < table.rows().get(r).size() ? table.rows().get(r).get(c) : "";
                                XWPFRun run = row.getCell(c).getParagraphs().get(0).createRun();
                                run.setText(text);
                                run.setBold(r == 0);
                            }
                        }
                        docx.createParagraph();   // space after the table
                    }
                }
                first = false;
            }
            docx.getProperties().getCoreProperties().setTitle(policy.title);
            docx.getProperties().getCoreProperties().setCreator(COMPANY + " - People and Culture (fictional sample document)");
            try (var out = Files.newOutputStream(file)) {
                docx.write(out);
            }
        }
    }

    /** Word needs a style definition, otherwise "Heading1" is only a name and looks like normal text. */
    static void addHeadingStyle(XWPFStyles styles, String id, String name, int outlineLevel, int size) {
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(id);
        style.addNewName().setVal(name);
        style.addNewBasedOn().setVal("Normal");
        style.addNewNext().setVal("Normal");
        style.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel));
        style.getPPr().addNewKeepNext();
        style.addNewRPr().addNewB();
        style.getRPr().addNewSz().setVal(BigInteger.valueOf(size * 2L));
        XWPFStyle xwpfStyle = new XWPFStyle(style);
        xwpfStyle.setType(STStyleType.PARAGRAPH);
        styles.addStyle(xwpfStyle);
    }

    // ===================================================================
    // The Complete Employee Handbook (exactly 500 pages)
    // ===================================================================

    static int writeHandbook(Path file, List<Policy> policies) throws IOException {
        PdfBuilder pdf = new PdfBuilder("Complete Employee Handbook 2026");

        // cover page and 2 reserved pages for the table of contents (written at the end, when the page numbers are known)
        pdf.newPage();
        pdf.spacer(170);
        pdf.paragraph(COMPANY, pdf.bold, PdfBuilder.MARGIN, PdfBuilder.TEXT_WIDTH, 20);
        pdf.spacer(10);
        pdf.paragraph("Complete Employee Handbook 2026", pdf.bold, PdfBuilder.MARGIN, PdfBuilder.TEXT_WIDTH, 26);
        pdf.spacer(24);
        pdf.paragraph("All policies of People and Culture in one document: leave, code of conduct, work-life balance, benefits, "
                + "remote work, IT security and data privacy, health, safety and travel, recruitment, performance and exit. "
                + "The annexes contain the office calendars, the entitlements per grade, the catalogue of HR forms, the rules "
                + "of the departments, frequently asked questions, a glossary and the course datasheets of the Northwind Academy.",
                pdf.regular);
        pdf.spacer(20);
        pdf.paragraph("Edition 2026-01. Valid from 1 January 2026. Owner: People and Culture. Questions: hr-help@northwind.example "
                + "or +1 (555) 010-4400.", pdf.regular);
        pdf.spacer(30);
        pdf.paragraph("This is a SAMPLE document of a fictional company. All names, places, telephone numbers and e-mail addresses "
                + "are invented. Policies are written for testing a document question-answering application.", pdf.italic);
        pdf.newPage();   // contents, page 2
        int firstTocPage = pdf.pageCount();
        pdf.newPage();
        int reservedTocPages = 2;

        // chapters: the 8 policies
        int chapter = 0;
        for (Policy policy : policies) {
            pdf.newPage();
            chapter++;
            List<Block> blocks = new ArrayList<>(policy.blocks);
            blocks.set(0, new Heading(1, "Part " + chapter + " - " + policy.title));
            pdf.render(blocks, false);
        }

        // annexes
        Annexes annexes = new Annexes();
        pdf.newPage();
        pdf.render(annexes.officeCalendars(), false);
        pdf.newPage();
        pdf.render(annexes.gradeTables(), false);
        pdf.newPage();
        pdf.render(annexes.formsCatalogue(), false);
        pdf.newPage();
        pdf.render(annexes.departmentRules(), false);
        pdf.newPage();
        pdf.render(annexes.faq(), false);
        pdf.newPage();
        pdf.render(annexes.glossary(), false);

        // Annex G: course datasheets, one per page, until the handbook has exactly 500 pages
        pdf.newPage();
        pdf.heading(1, "Annex G - Northwind Academy course datasheets");
        pdf.paragraph("Every course has one datasheet. Courses are booked in PeopleHub under My Learning. The learning budget "
                + "of the employee pays the fee (see the Employee Benefits Guide).", pdf.regular);
        int course = 0;
        while (pdf.pageCount() < HANDBOOK_PAGES) {
            pdf.newPage();
            pdf.render(annexes.course(course++), false);
        }
        if (pdf.pageCount() > HANDBOOK_PAGES) {
            throw new IllegalStateException("The handbook has " + pdf.pageCount() + " pages, more than " + HANDBOOK_PAGES
                    + ". Make the annexes smaller.");
        }

        // table of contents: chapters and the sections of the 8 policies
        List<PdfBuilder.Entry> toc = new ArrayList<>();
        boolean inPolicies = true;
        for (PdfBuilder.Entry entry : pdf.entries) {
            if (entry.level() == 1) {
                inPolicies = entry.text().startsWith("Part ");
                toc.add(entry);
            } else if (entry.level() == 2 && inPolicies) {
                toc.add(entry);
            }
        }
        pdf.drawTableOfContents(firstTocPage, reservedTocPages, toc);
        return pdf.save(file, "Northwind Ltd - Complete Employee Handbook 2026");
    }

    // ===================================================================
    // Generated annexes. All data comes from tables and rules, so the facts are consistent
    // with each other and with the policies.
    // ===================================================================

    static class Annexes {

        // ----- the grades (must match the policies: leave days, learning budget, bonus, limits) -----

        static final String[] GRADE_TITLES = {"Trainee", "Assistant", "Associate", "Specialist", "Senior Specialist", "Team Lead",
                "Manager", "Senior Manager", "Director", "Senior Director", "Vice President", "Executive Vice President"};
        static final int[] LEARNING_BUDGET = {1500, 1500, 2000, 2000, 2500, 2500, 3000, 3000, 4000, 4000, 5000, 5000};

        static int leaveDays(int grade) {
            return grade <= 5 ? 25 : grade <= 9 ? 27 : 30;
        }

        static int bonusPercent(int grade) {
            return grade <= 4 ? 5 : grade <= 8 ? 8 : grade <= 10 ? 12 : 20;
        }

        static int expenseLimit(int grade) {
            return grade <= 3 ? 500 : grade <= 6 ? 1500 : grade <= 9 ? 5000 : 15000;
        }

        static String notice(int grade) {
            return grade <= 9 ? "3 months" : "6 months";
        }

        // ----- Annex A: offices -----

        static final String[] OFFICES = {"Aldermoor", "Brightwater", "Cinderford", "Dunmere", "Eastbrook", "Fairhaven", "Glenmoor",
                "Harrowgate", "Ironbridge", "Juniper Bay", "Kestrel Point", "Lakemont", "Marlow Ridge", "Northgate", "Oakhurst",
                "Pinecrest", "Queensferry", "Ravenswood", "Stonebridge", "Thornfield", "Upton Vale", "Valemont", "Westhollow",
                "Yarrowdale", "Zephyr Bay", "Ashgrove", "Birchmoor", "Copperfield", "Driftwood", "Elmsworth", "Foxglen", "Greystone",
                "Hawthorne", "Inverlake", "Jasper Hill", "Kingsmere", "Larkspur", "Mistral Cove", "Nettlebrook", "Orchardvale"};
        static final String[] REGIONS = {"Americas", "Europe", "Asia-Pacific", "Middle East and Africa"};
        static final String[] HOURS = {"7:30 to 18:30", "8:00 to 19:00", "8:30 to 18:00", "7:00 to 19:30"};
        static final String[] HOLIDAYS = {"Founders Day", "Harbor Festival", "Spring Lantern Day", "Midsummer Fair", "Harvest Day",
                "Lighthouse Day", "Maple Festival", "Winter Lights", "Unity Day", "River Day", "Peace Day", "Remembrance Day",
                "Mountain Day", "Seafarers Day", "Orchard Day", "Kite Day", "Cultural Heritage Day", "Independence Day"};
        static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
                "October", "November", "December"};

        static String slug(String name) {
            return name.toLowerCase(Locale.ROOT).replace(' ', '-');
        }

        static int headcount(int office) {
            return 40 + (office * 53) % 560;
        }

        static String hours(int office) {
            return HOURS[office % HOURS.length];
        }

        static String phone(int office) {
            return "+1 (555) 020-" + String.format("%04d", 1000 + 37 * office);
        }

        /** The public holidays of an office: always 3 fixed days, plus 7 to 10 local days. */
        static List<String[]> holidays(int office) {
            Random random = new Random(office * 7919L + 13);
            List<String[]> days = new ArrayList<>();
            days.add(new String[] {"1 January", "New Year's Day"});
            days.add(new String[] {"1 May", "Labour Day"});
            days.add(new String[] {"25 December", "Year-End Day"});
            int local = 7 + random.nextInt(4);
            List<String> pool = new ArrayList<>(List.of(HOLIDAYS));
            List<int[]> dates = new ArrayList<>();
            while (dates.size() < local) {
                int month = 1 + random.nextInt(10);   // February to November
                int day = 1 + random.nextInt(28);
                if (dates.stream().noneMatch(d -> d[0] == month && d[1] == day)) {
                    dates.add(new int[] {month, day});
                }
            }
            dates.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            for (int[] date : dates) {
                days.add(new String[] {date[1] + " " + MONTHS[date[0]], pool.remove(random.nextInt(pool.size()))});
            }
            days.sort((a, b) -> Integer.compare(sortKey(a[0]), sortKey(b[0])));
            return days;
        }

        private static int sortKey(String date) {
            String[] parts = date.split(" ");
            for (int m = 0; m < MONTHS.length; m++) {
                if (MONTHS[m].equals(parts[1])) {
                    return m * 100 + Integer.parseInt(parts[0]);
                }
            }
            return 0;
        }

        List<Block> officeCalendars() {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex A - Offices, opening hours and public holidays 2026"));
            blocks.add(new Para("This annex lists every Northwind office with its opening hours, its public holidays in 2026, and the "
                    + "local contacts. Public holidays are not deducted from annual leave. When a public holiday falls on a weekend, "
                    + "it is not replaced by a day off, except where the office page says so."));
            for (int i = 0; i < OFFICES.length; i++) {
                String name = OFFICES[i];
                int staff = headcount(i);
                List<String[]> holidays = holidays(i);
                blocks.add(new Heading(2, "Office " + name));
                blocks.add(new Para("The " + name + " office belongs to the region " + REGIONS[i % REGIONS.length] + " and has about "
                        + staff + " employees. The address is " + (10 + i * 3) + " Harbour Road, " + name + ". The building is open "
                        + "from " + hours(i) + " on working days. The reception desk can be reached at " + phone(i) + "."));
                blocks.add(new Heading(3, name + " - public holidays 2026"));
                blocks.add(new Para("The " + name + " office observes " + holidays.size() + " public holidays in 2026."));
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("Date", "Public holiday"));
                for (String[] day : holidays) {
                    rows.add(List.of(day[0], day[1]));
                }
                blocks.add(new Table(rows));
                blocks.add(new Heading(3, name + " - local services and contacts"));
                blocks.add(new Bullets(List.of(
                        "Local HR contact: hr." + slug(name) + "@northwind.example",
                        "Security and emergency desk: " + phone(i) + ", extension " + (200 + i) + ", staffed during opening hours; outside these hours call +1 (555) 010-9911.",
                        "Canteen: " + (i % 3 == 0 ? "no canteen, employees receive meal vouchers of 8 USD per working day"
                                : "open from 11:30 to 14:00 on working days") + ".",
                        "Parking: " + (20 + (i * 7) % 120) + " places in car park B for employees (free of charge) and 6 visitor places in car park A.",
                        "Fire assembly point: car park A. The office has " + Math.max(2, staff / 20) + " fire wardens and "
                                + Math.max(2, staff / 30) + " first aiders.",
                        "Bicycle racks and showers are next to the main entrance.")));
            }
            return blocks;
        }

        // ----- Annex B: one section per grade -----

        List<Block> gradeTables() {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex B - Entitlements by grade"));
            blocks.add(new Para("Every grade from G1 to G12 has its own entitlements. The values below are valid from 1 January 2026 and "
                    + "follow the Leave Policy, the Employee Benefits Guide, the Health, Safety and Business Travel Policy and the "
                    + "Recruitment, Performance and Exit Policy."));
            for (int g = 1; g <= 12; g++) {
                String title = GRADE_TITLES[g - 1];
                blocks.add(new Heading(2, "Grade G" + g + " - " + title));
                blocks.add(new Para("Grade G" + g + " is the grade of the " + title + ". " + levelDescription(g)));
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("Entitlement", "Grade G" + g));
                rows.add(List.of("Annual leave per year", leaveDays(g) + " working days"));
                rows.add(List.of("Annual learning budget", String.format("%,d", LEARNING_BUDGET[g - 1]) + " USD"));
                rows.add(List.of("Target annual bonus", bonusPercent(g) + " percent of the annual salary"));
                rows.add(List.of("Notice period after probation", notice(g)));
                rows.add(List.of("Expense approval limit", String.format("%,d", expenseLimit(g)) + " USD"));
                rows.add(List.of("Flights", g >= 8 ? "Economy class; business class for flights of more than 8 hours" : "Economy class"));
                rows.add(List.of("Hotel maximum per night", "250 USD (tier 1 cities), 200 USD (tier 2), 150 USD (other places)"));
                rows.add(List.of("Meal allowance when travelling", "55 USD per day"));
                rows.add(List.of("Overtime", g >= 10 ? "Not paid, it is part of the role" : "Paid at 1.5 times (weekdays) or 2 times (weekends and holidays)"));
                rows.add(List.of("On-call allowance", g >= 9 ? "None" : "250 USD per week of on-call duty"));
                rows.add(List.of("Laptop replacement", g >= 6 ? "Every 3 years" : "Every 4 years"));
                blocks.add(new Table(rows));
                blocks.add(new Para("Typical time in grade G" + g + " before a promotion is considered: " + (g <= 3 ? 1 + g / 2 : 2 + g / 3)
                        + " years. Promotions are decided in the promotion cycle in April and need at least 6 months of work at the higher "
                        + "grade as evidence (see the Recruitment, Performance and Exit Policy)."));
            }
            return blocks;
        }

        private String levelDescription(int g) {
            return switch ((g - 1) / 3) {
                case 0 -> "Employees in this grade work on defined tasks with guidance and learn the profession.";
                case 1 -> "Employees in this grade work independently, own results in their area and support colleagues.";
                case 2 -> "Employees in this grade lead teams or large projects and are responsible for results and budgets.";
                default -> "Employees in this grade lead departments or business units and shape the strategy of Northwind.";
            };
        }

        // ----- Annex C: forms -----

        static final String[] FORM_PURPOSES = {"change of bank details", "change of home address", "change of name", "update of the emergency contact",
                "flexible working request", "compressed week request", "unpaid leave request", "study leave request", "volunteering leave request",
                "new equipment request", "software licence request", "access to a system", "parking permit", "locker and key request",
                "visa support letter", "employment confirmation letter", "internal transfer application", "promotion nomination",
                "secondment request", "training course request", "conference attendance request", "certification exam fee refund",
                "gym allowance claim", "childcare allowance claim", "cycle-to-work scheme", "meal voucher change", "public transport pass claim",
                "business card order", "name badge replacement", "ergonomic assessment request", "workplace adjustment request",
                "referral bonus claim", "long service award", "spot award nomination", "stock purchase plan enrolment", "pension contribution change",
                "life insurance beneficiary change", "salary sacrifice request", "tax certificate request", "reference letter request"};
        static final String[] APPROVERS = {"the line manager", "the head of department", "People and Culture", "Finance", "IT Security", "Facilities"};

        List<Block> formsCatalogue() {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex C - Catalogue of HR forms"));
            blocks.add(new Para("All forms are available in PeopleHub under Forms. Each form has a code that starts with HR followed by a "
                    + "number. This catalogue explains for every form what it is for, who completes it, who approves it and how long the "
                    + "processing takes. Forms are submitted in PeopleHub; paper forms are not accepted."));
            for (int code = 101; code <= 250; code++) {
                String[] fixed = fixedForm(code);
                String purpose = fixed != null ? fixed[0] : FORM_PURPOSES[(code * 7) % FORM_PURPOSES.length];
                String approver = fixed != null ? fixed[1] : APPROVERS[(code * 5) % APPROVERS.length];
                String days = fixed != null ? fixed[2] : String.valueOf(2 + (code * 3) % 14);
                String note = fixed != null ? fixed[3] : "Attach the supporting document if the form asks for it.";
                blocks.add(new Heading(3, "Form HR-" + code + " - " + capitalize(purpose)));
                blocks.add(new Para("Form HR-" + code + " is used for the " + purpose + ". The employee completes the form in PeopleHub. "
                        + "The form is approved by " + approver + ". The normal processing time is " + days + " working days. " + note));
            }
            return blocks;
        }

        /** Forms that are named in the policies. {purpose, approver, processing days, note} */
        private String[] fixedForm(int code) {
            return switch (code) {
                case 118 -> new String[] {"request to work from abroad", "People and Culture, Finance and Legal", "20",
                        "The request must be submitted at least 4 weeks in advance. Employees may work abroad for up to 20 working days per calendar year."};
                case 125 -> new String[] {"notice of maternity leave", "People and Culture", "5",
                        "Submit it at least 15 weeks before the expected date of birth."};
                case 126 -> new String[] {"request for paternity or partner leave", "People and Culture", "5",
                        "Submit it at least 8 weeks before the expected date of birth. The leave must be taken within 6 months of the birth."};
                case 130 -> new String[] {"sabbatical request", "the head of department and People and Culture", "15",
                        "Available after 7 years of service. Submit it at least 6 months in advance."};
                case 142 -> new String[] {"approval of outside work", "the line manager and People and Culture", "10",
                        "Work for another employer or a board position needs written approval before it starts."};
                case 150 -> new String[] {"relocation package", "People and Culture and Finance", "10",
                        "The relocation payment is 2,500 USD. It must be repaid in full when the employee leaves within 12 months after the move."};
                case 160 -> new String[] {"company credit card", "Finance", "7",
                        "The card is used for business expenses only. Receipts are uploaded within 10 working days after the statement."};
                case 204 -> new String[] {"expense claim", "the line manager", "10",
                        "Submit it within 30 days after the trip or purchase. A receipt is needed for every expense above 25 USD."};
                case 210 -> new String[] {"accident and incident report", "Facilities and the safety officer", "2",
                        "Every accident, near miss or unsafe situation must be reported within 24 hours."};
                default -> null;
            };
        }

        // ----- Annex D: departments -----

        static final String[] DEPARTMENTS = {"Finance", "Legal", "People and Culture", "IT Operations", "Software Engineering",
                "Data and Analytics", "Security", "Facilities", "Procurement", "Sales Americas", "Sales Europe", "Sales Asia-Pacific",
                "Marketing", "Customer Support", "Customer Success", "Product Management", "Design", "Quality Assurance", "Research",
                "Laboratory Services", "Logistics", "Manufacturing Planning", "Internal Audit", "Communications", "Corporate Strategy",
                "Investor Relations", "Real Estate", "Travel Desk", "Training Academy", "Compliance", "Risk Management", "Tax", "Treasury",
                "Business Intelligence", "Executive Office", "Innovation Lab"};
        static final String[] BLACKOUTS = {"the first 3 working days of every month (month-end close)", "the last 2 weeks of every quarter",
                "the week of the annual customer conference", "the first week of January", "the last week of June and the last week of December",
                "no blackout periods"};
        static final String[] ROTAS = {"No on-call duty.", "On-call rota of 1 week in every 4 weeks, with an on-call allowance of 250 USD per week.",
                "On-call rota of 1 week in every 6 weeks, with an on-call allowance of 250 USD per week.",
                "Weekend duty once per quarter, with a day off in return."};

        List<Block> departmentRules() {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex D - Rules of the departments"));
            blocks.add(new Para("Some departments have rules in addition to the company policies, because of the nature of their work. "
                    + "The company policies always apply. Where a department rule differs, it is listed here."));
            for (int d = 0; d < DEPARTMENTS.length; d++) {
                String name = DEPARTMENTS[d];
                int staff = 12 + (d * 37) % 240;
                blocks.add(new Heading(2, "Department " + name));
                blocks.add(new Para("The " + name + " department has about " + staff + " employees. Its contact address is dept."
                        + slug(name) + "@northwind.example. The head of the department is the Head of " + name + "."));
                blocks.add(new Heading(3, name + " - working time and leave"));
                blocks.add(new Bullets(List.of(
                        "Core collaboration hours: " + (d % 4 == 0 ? "10:00 to 15:00" : "10:00 to 16:00") + ".",
                        "Leave blackout: " + BLACKOUTS[d % BLACKOUTS.length] + ". Blackout periods are published in PeopleHub at least 60 days in advance.",
                        ROTAS[d % ROTAS.length],
                        "Remote work: " + (d % 7 == 0 ? "up to 2 days per week (the department needs presence in the office)"
                                : "up to 3 days per week, team days on Tuesday and Thursday") + ".")));
                blocks.add(new Heading(3, name + " - workplace and tools"));
                blocks.add(new Bullets(List.of(
                        "Dress code: " + (d % 9 == 3 ? "safety clothing and safety shoes are mandatory in work areas"
                                : d % 5 == 0 ? "smart business clothes when meeting customers" : "business casual, casual Fridays") + ".",
                        "Required training in the first 30 days: security awareness, data protection" + (d % 4 == 1 ? ", and the department safety induction" : "") + ".",
                        "Main tools: " + (d % 3 == 0 ? "the finance ledger and the planning tool" : d % 3 == 1 ? "the ticket system and the knowledge base"
                                : "the project board and the shared document library") + ".",
                        "Approval of expenses above 1,500 USD: " + (d % 2 == 0 ? "the head of the department" : "the head of the department and Finance") + ".")));
            }
            return blocks;
        }

        // ----- Annex E: FAQ -----

        private static final String[][] FAQ = {
                {"How many days of sick leave on full pay do I get per year?", "12 days on full pay. After that, 50 percent of salary for up to 40 further working days."},
                {"When must I report that I am sick?", "Before 9:30 on the first day of absence, by phone or message to the manager."},
                {"When do I need a medical certificate?", "When sickness lasts more than 3 consecutive days. It is uploaded to PeopleHub within 2 working days of the doctor's visit."},
                {"How long is maternity leave?", "26 weeks on full pay, and up to 26 further weeks unpaid."},
                {"How long is paternity leave?", "4 weeks on full pay, within 6 months of the birth. Use form HR-126."},
                {"Which form do I use for maternity leave?", "Form HR-125, at least 15 weeks before the expected date of birth."},
                {"How many days of bereavement leave do I get when a parent dies?", "5 paid days. For a sibling or grandparent it is 3 days, for another close relative or friend 1 day."},
                {"How many days of marriage leave do I get?", "3 paid days, to be taken within 6 months of the wedding."},
                {"How many annual leave days can I carry over to the next year?", "Up to 5 days. Carried-over days expire on 31 March."},
                {"How much notice do I need for annual leave of more than 5 days?", "At least 10 working days."},
                {"How quickly must my manager answer a leave request?", "Within 3 working days. After that, the request goes to the manager of the manager."},
                {"How long is the sabbatical and who can take it?", "4 weeks, paid, after 7 years of service. Request it with form HR-130 at least 6 months in advance."},
                {"How much parental leave can I take per child?", "Up to 18 weeks of unpaid parental leave per child until the child is 8 years old."},
                {"From which value must I declare a gift?", "Gifts and hospitality of more than 75 USD must be declared to the manager within 5 working days."},
                {"Which form do I need for a second job?", "Form HR-142. Outside work needs written approval before it starts."},
                {"What are the standard working hours?", "40 hours per week, normally 9:00 to 17:30 with a lunch break of 30 minutes."},
                {"What are the core collaboration hours?", "10:00 to 16:00 local time."},
                {"How is overtime paid?", "1.5 times the hourly rate on weekdays and 2 times on weekends and public holidays. It needs prior approval and is limited to 8 hours per week. Grades G10 and above are not paid for overtime."},
                {"How long does an investigation of a harassment report take?", "The report is confirmed within 2 working days and the investigation is completed within 10 working days."},
                {"How can I report a concern anonymously?", "Through the Speak Up line: speakup@northwind.example or +1 (555) 010-6600, open 24 hours a day."},
                {"What is the dress code?", "Business casual, with casual Fridays. Smart business clothes are worn when meeting customers."},
                {"May I bring my dog to the office?", "No. Pets are not allowed, except guide dogs and assistance dogs."},
                {"Where can employees park?", "Free of charge in car park B during working hours. Visitors use car park A."},
                {"Between which times can I start work?", "Between 7:30 and 10:00, as long as you work your contracted hours and are available from 10:00 to 16:00."},
                {"What is the right to disconnect?", "No emails or messages are expected after 19:00, at weekends or during leave, unless you are on call."},
                {"Which afternoon is free of meetings?", "Wednesday afternoon from 13:00."},
                {"How many wellbeing days do I get?", "1 paid wellbeing day per year, in addition to annual leave."},
                {"How many wellness hours do I get?", "Up to 3 paid wellness hours per month."},
                {"How do I reach the Employee Assistance Programme?", "Call +1 (555) 010-5500, available 24 hours a day. Each person can use up to 8 counselling sessions per issue per year."},
                {"How much is the childcare allowance?", "150 USD per month for each child up to 6 years of age, with a maximum of 450 USD per month."},
                {"How much of my health insurance does the company pay?", "100 percent of the premium for the employee and 60 percent for dependants."},
                {"How much does dental insurance cover?", "Up to 1,200 USD per person per year."},
                {"How does the pension match work?", "The company matches contributions up to 6 percent of the gross salary. The matching part belongs to the employee after 2 years of service."},
                {"How does the stock purchase plan work?", "Employees can buy shares with up to 5 percent of their salary at a discount of 10 percent. Shares can be sold after 12 months."},
                {"How much is the home office allowance?", "A one-time allowance of 400 USD plus 50 USD per month for internet and phone, for employees who work remotely at least 2 days a week."},
                {"How much is the gym allowance?", "40 USD per month, paid after uploading the receipt in PeopleHub."},
                {"How much are the meal vouchers?", "8 USD per working day at offices without a canteen."},
                {"How high is the employee referral bonus?", "1,000 USD, paid after the new colleague has completed 6 months."},
                {"How many days per week can I work remotely?", "Up to 3 days per week. The team days are Tuesday and Thursday."},
                {"May I work from another country?", "For up to 20 working days per calendar year, with approval through form HR-118 at least 4 weeks in advance."},
                {"How long must my password be?", "At least 14 characters. Multi-factor authentication is mandatory."},
                {"How fast must I report a security incident?", "Within 1 hour, to security@northwind.example or +1 (555) 010-9911."},
                {"After how many minutes does my screen lock?", "Automatically after 5 minutes of inactivity."},
                {"How do I make a request about my personal data?", "Write to privacy@northwind.example. The company answers within 30 days."},
                {"How long is a personnel file kept after I leave?", "7 years after the end of employment."},
                {"Where is the fire assembly point?", "In car park A. The emergency desk is at +1 (555) 010-7788."},
                {"How fast must I report an accident?", "Within 24 hours, with form HR-210 or in PeopleHub."},
                {"Which flight class may I book?", "Economy class. Employees of grade G8 and above may book business class for flights of more than 8 hours."},
                {"How much is the daily meal allowance when travelling?", "55 USD per day, paid without receipts."},
                {"How much is the mileage allowance for a private car?", "0.60 USD per kilometre."},
                {"What is the deadline for an expense claim?", "30 days, with form HR-204. A receipt is needed for every expense above 25 USD."},
                {"How long is the probation period?", "6 months, with conversations at the end of month 1, month 3 and month 5."},
                {"What is the notice period?", "1 month during probation. After probation 3 months for grades G1 to G9 and 6 months for grades G10 to G12."},
                {"When do performance reviews take place?", "Twice a year: the mid-year review in June and the year-end review in December. Ratings go from 1 to 5."},
                {"How long is a performance improvement plan?", "90 days."},
                {"When is the promotion cycle?", "In April."},
                {"When do I receive my final salary after leaving?", "Within 14 days after the last working day, including unused leave of the current year."},
                {"How much is the severance in case of redundancy?", "1 month of salary for each full year of service, with a minimum of 3 months."}};

        List<Block> faq() {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex E - Frequently asked questions"));
            blocks.add(new Para("Short answers to the questions that People and Culture receives most often. The policies have the full rules. "
                    + "If an answer here differs from a policy, the policy applies and People and Culture corrects the FAQ."));
            for (String[] qa : FAQ) {
                blocks.add(new Heading(3, qa[0]));
                blocks.add(new Para(qa[1]));
            }
            for (int g = 1; g <= 12; g++) {
                blocks.add(new Heading(3, "How many days of annual leave does an employee of grade G" + g + " get?"));
                blocks.add(new Para("Employees of grade G" + g + " (" + GRADE_TITLES[g - 1] + ") get " + leaveDays(g) + " working days of annual leave per year."));
                blocks.add(new Heading(3, "How high is the learning budget of grade G" + g + "?"));
                blocks.add(new Para("The annual learning budget of grade G" + g + " is " + String.format("%,d", LEARNING_BUDGET[g - 1]) + " USD."));
                blocks.add(new Heading(3, "How long is the notice period of grade G" + g + " after probation?"));
                blocks.add(new Para("The notice period of grade G" + g + " after probation is " + notice(g) + "."));
                blocks.add(new Heading(3, "Up to which amount may a manager of grade G" + g + " approve expenses?"));
                blocks.add(new Para("A manager of grade G" + g + " approves expenses up to " + String.format("%,d", expenseLimit(g)) + " USD."));
            }
            for (int i = 0; i < OFFICES.length; i++) {
                blocks.add(new Heading(3, "What are the opening hours of the " + OFFICES[i] + " office?"));
                blocks.add(new Para("The " + OFFICES[i] + " office is open from " + hours(i) + " on working days. The reception desk is at " + phone(i) + "."));
                blocks.add(new Heading(3, "How many public holidays does the " + OFFICES[i] + " office have in 2026?"));
                blocks.add(new Para("The " + OFFICES[i] + " office observes " + holidays(i).size() + " public holidays in 2026."));
            }
            return blocks;
        }

        // ----- Annex F: glossary -----

        List<Block> glossary() {
            String[][] terms = {
                    {"Annual leave", "Paid days off that an employee can take every year. 25, 27 or 30 working days, depending on the grade."},
                    {"Blackout period", "A period in which a department does not approve leave, for example month-end close in Finance."},
                    {"Core collaboration hours", "The hours from 10:00 to 16:00 in which everybody is available."},
                    {"EAP", "Employee Assistance Programme: free and confidential counselling, 24 hours a day at +1 (555) 010-5500."},
                    {"ESPP", "Employee stock purchase plan: employees buy shares with up to 5 percent of salary at a discount of 10 percent."},
                    {"Garden leave", "The employee is released from work during the notice period but still receives pay."},
                    {"Grade", "The level of a role, from G1 (Trainee) to G12 (Executive Vice President). Many entitlements depend on the grade."},
                    {"Gross misconduct", "A very serious breach, for example theft, violence or fraud, that can lead to immediate dismissal."},
                    {"HR helpdesk", "The central contact of People and Culture: hr-help@northwind.example or +1 (555) 010-4400."},
                    {"Hybrid work", "A mix of work in the office and work at home. Up to 3 remote days per week."},
                    {"Learning budget", "The yearly amount per employee for courses, conferences, books and certificates."},
                    {"MFA", "Multi-factor authentication: a second proof of identity, for example an authenticator app."},
                    {"Northwind Academy", "The internal training organisation with free courses on leadership, data skills, languages and security."},
                    {"On-call duty", "Being available to handle urgent problems outside working hours. Paid with 250 USD per week."},
                    {"PeopleHub", "The HR portal for leave, forms, benefits, goals and reviews."},
                    {"PIP", "Performance improvement plan: 90 days with clear goals and support for an employee with a low rating."},
                    {"Probation", "The first 6 months of employment, with a notice period of 1 month."},
                    {"Pro rata", "Calculated in proportion to the working hours or the time worked."},
                    {"Right to disconnect", "No expectation to answer messages after 19:00, at weekends or during leave."},
                    {"Sabbatical", "4 weeks of paid leave after 7 years of service."},
                    {"Speak Up line", "The anonymous reporting channel: speakup@northwind.example or +1 (555) 010-6600."},
                    {"Team days", "The days on which the team works in the office together: Tuesday and Thursday."},
                    {"Tier 1 city", "A capital or financial centre. The hotel limit is 250 USD per night."},
                    {"Tier 2 city", "A large city that is not tier 1. The hotel limit is 200 USD per night."},
                    {"Wellbeing day", "One paid day per year for rest, in addition to annual leave."}};
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(1, "Annex F - Glossary"));
            blocks.add(new Para("Terms and abbreviations used in the policies, in alphabetical order."));
            List<String[]> sorted = new ArrayList<>(List.of(terms));
            sorted.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
            for (String[] term : sorted) {
                blocks.add(new Para(term[0] + ": " + term[1]));
            }
            for (int code = 101; code <= 250; code++) {
                String[] fixed = fixedForm(code);
                String purpose = fixed != null ? fixed[0] : FORM_PURPOSES[(code * 7) % FORM_PURPOSES.length];
                blocks.add(new Para("HR-" + code + ": form for the " + purpose + "."));
            }
            return blocks;
        }

        // ----- Annex G: one course datasheet -----

        static final String[] COURSE_TOPICS = {"Leading without authority", "Giving and receiving feedback", "Effective meetings", "Time management",
                "Negotiation basics", "Presentation skills", "Project management essentials", "Agile ways of working", "Data literacy",
                "Spreadsheets for analysis", "Introduction to SQL", "Introduction to Python", "Cloud fundamentals", "Cyber security awareness",
                "Data protection in practice", "Inclusive leadership", "Unconscious bias", "Managing remote teams", "Conflict resolution",
                "Coaching conversations", "Business writing", "Customer communication", "Financial basics for managers", "Budgeting and forecasting",
                "Procurement basics", "Contract basics", "Workplace safety induction", "First aid refresher", "Mental health first aid",
                "Stress management", "Career planning", "Mentoring programme", "Interview skills for managers", "Performance conversations",
                "Change management", "Strategic thinking", "Design thinking", "Quality management", "Risk management", "Business English",
                "Business German", "Business Spanish", "Business French", "Onboarding for new managers", "Public speaking",
                "Critical thinking", "Decision making", "Emotional intelligence", "Sustainability at Northwind", "Ethics and compliance"};
        static final String[] DELIVERY = {"classroom", "online with a trainer", "self-study online", "blended (online and classroom)"};

        List<Block> course(int index) {
            String topic = COURSE_TOPICS[index % COURSE_TOPICS.length] + (index >= COURSE_TOPICS.length ? ", level " + (1 + index / COURSE_TOPICS.length) : "");
            String code = "NA-" + (100 + index);
            int hours = 2 + (index * 3) % 14;
            int fee = 150 + (index * 45) % 1200;
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Heading(2, "Course " + code + " - " + topic));
            blocks.add(new Para("The course " + topic + " has the code " + code + ". It takes " + hours + " hours in total and is given as "
                    + DELIVERY[index % DELIVERY.length] + ". The fee is " + String.format("%,d", fee) + " USD per participant and is paid "
                    + "from the learning budget of the employee."));
            blocks.add(new Table(List.of(
                    List.of("Item", "Course " + code),
                    List.of("Title", topic),
                    List.of("Duration", hours + " hours"),
                    List.of("Delivery", DELIVERY[index % DELIVERY.length]),
                    List.of("Group size", String.valueOf(8 + (index * 5) % 17) + " participants"),
                    List.of("Fee", String.format("%,d", fee) + " USD"),
                    List.of("Target group", "Grades G" + (1 + index % 4) + " to G" + (5 + index % 8)),
                    List.of("Language", index % 9 == 0 ? "German" : index % 11 == 0 ? "Spanish" : "English"),
                    List.of("Certificate", index % 3 == 0 ? "Yes, after a short test" : "Certificate of attendance"),
                    List.of("Booking", "PeopleHub under My Learning, at least 10 working days before the start"))));
            blocks.add(new Para("Participants complete a short preparation task before the course. Cancellation up to 5 working days before the start is "
                    + "free. Later cancellation costs 50 percent of the fee, unless a colleague takes the place. Questions: academy@northwind.example."));
            return blocks;
        }

        static String capitalize(String text) {
            return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
        }
    }
}
