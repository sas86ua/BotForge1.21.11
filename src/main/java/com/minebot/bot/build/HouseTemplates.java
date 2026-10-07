package com.minebot.bot.build;

import java.util.List;

/**
 * Plans of the bigger house a bot builds after some weeks in its first hut:
 * 7 wide, 10 deep, a stone base, wooden walls with windows, a wooden roof.
 * Each plan is a list of horizontal layers from the floor up; each layer has
 * 10 rows (z = 0 is the front, with the door) of 7 characters (x = 0..6).
 *
 * <pre>
 *   C stone (cobblestone or the like)   P planks   L log
 *   G window (glass or glass pane)      D door (lower half; the upper half comes with it)
 *   B bed (foot; head towards the back) T crafting table
 *   H chest                             S smoker            t wall torch
 *   . must be empty                     (space) leave as it is
 * </pre>
 * The first layer is the floor (one below where the bot stands inside).
 */
public final class HouseTemplates {
    public record Template(String name, List<String[]> layers) {
        public static final int WIDTH = 7;
        public static final int DEPTH = 10;

        public char at(int x, int y, int z) {
            return layers.get(y).length > z && layers.get(y)[z].length() > x ? layers.get(y)[z].charAt(x) : ' ';
        }

        public int height() {
            return layers.size();
        }
    }

    private static final String[] FLOOR = rows("CCCCCCC");

    /** Same, with a bed and crafting table at the back. */
    private static final String[] BASE_FURNISHED = {
        "CCCDCCC", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "C....SC", "CB....C", "C..H.TC", "CCCCCCC"};

    private static final String[] CEILING = rows("PPPPPPP");

    public static final List<Template> ALL = List.of(
        // Cottage: log corners, windows all round, stepped hip roof
        new Template("cottage", List.of(
            FLOOR,
            BASE_FURNISHED,
            lit(new String[] {"LGP.PGL", "P.....P", "G.....G", "P.....P", "P.....P", "G.....G", "P.....P", "P.....P", "P.....P", "LPPGPPL"}),
            new String[] {"LPPPPPL", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "LPPPPPL"},
            CEILING,
            inset(1, "PPPPP"),
            inset(2, "PPP"),
            inset(3, "L"))),
        // Long house: stone up to the windows, gable roof along the length
        new Template("long house", List.of(
            FLOOR,
            BASE_FURNISHED,
            lit(new String[] {"CCC.CCC", "C.....C", "C.....C", "G.....G", "C.....C", "C.....C", "G.....G", "C.....C", "C.....C", "CCCGCCC"}),
            new String[] {"LPPPPPL", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "LPPPPPL"},
            CEILING,
            gable(1),
            gable(2),
            gableRidge())),
        // Lodge: log walls with plank bands, big front windows, flat roof with a parapet
        new Template("lodge", List.of(
            FLOOR,
            BASE_FURNISHED,
            lit(new String[] {"LGG.GGL", "L.....L", "G.....G", "L.....L", "L.....L", "L.....L", "G.....G", "L.....L", "L.....L", "LLGGGLL"}),
            new String[] {"PPPPPPP", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "P.....P", "PPPPPPP"},
            CEILING,
            ring("LPPLPPL", "P.....P", "LPPLPPL"))),
        // Farmhouse: tall stone base, windows high up, steep stepped roof
        new Template("farmhouse", List.of(
            FLOOR,
            BASE_FURNISHED,
            lit(new String[] {"CCC.CCC", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "CCCCCCC"}),
            new String[] {"PGPPPGP", "G.....G", "P.....P", "P.....P", "G.....G", "P.....P", "P.....P", "G.....G", "P.....P", "PPGPGPP"},
            CEILING,
            gable(1),
            gable(2),
            gableRidge())));

    private HouseTemplates() {
    }

    /** Wall torches inside: on both side walls by the door and halfway along. */
    private static String[] lit(String[] layer) {
        String[] rows = layer.clone();
        for (int z : new int[] {1, 4}) {
            StringBuilder row = new StringBuilder(rows[z]);
            row.setCharAt(1, 't');
            row.setCharAt(Template.WIDTH - 2, 't');
            rows[z] = row.toString();
        }
        return rows;
    }

    private static String[] rows(String row) {
        String[] rows = new String[Template.DEPTH];
        java.util.Arrays.fill(rows, row);
        return rows;
    }

    /** A layer covering the house minus {@code margin} on each side. */
    private static String[] inset(int margin, String row) {
        String pad = " ".repeat(margin);
        String[] rows = new String[Template.DEPTH];
        for (int z = 0; z < Template.DEPTH; z++) {
            boolean inside = z >= margin && z < Template.DEPTH - margin;
            rows[z] = inside ? pad + row : "";
        }
        return rows;
    }

    /** Gable roof step: full length, {@code margin} in from the long sides, ends closed. */
    private static String[] gable(int margin) {
        String pad = " ".repeat(margin);
        String[] rows = new String[Template.DEPTH];
        for (int z = 0; z < Template.DEPTH; z++) {
            rows[z] = pad + "P".repeat(Template.WIDTH - 2 * margin);
        }
        return rows;
    }

    private static String[] gableRidge() {
        String[] rows = new String[Template.DEPTH];
        java.util.Arrays.fill(rows, "   L");
        return rows;
    }

    /** A parapet: the first row in front, the middle row along the sides, the last row at the back. */
    private static String[] ring(String front, String middle, String back) {
        String[] rows = new String[Template.DEPTH];
        rows[0] = front;
        for (int z = 1; z < Template.DEPTH - 1; z++) {
            rows[z] = middle.replace('.', ' ');
        }
        rows[Template.DEPTH - 1] = back;
        return rows;
    }
}
