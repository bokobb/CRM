package com.confectionery.crm.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Сумма прописью для накладной — "Два миллиона сто пятьдесят три тысячи шестьсот
 * пятьдесят теңге 00 тиын". Тенге/тиын не склоняются (как и в реальных бланках), но
 * тысяча/миллион/миллиард — да (одна тысяча / две тысячи / пять тысяч и т.п.).
 */
final class NumberToWordsRu {

    private NumberToWordsRu() {
    }

    private static final String[] UNITS_M =
            {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] UNITS_F =
            {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать",
            "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS =
            {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS =
            {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};

    private record Scale(String one, String few, String many, boolean feminine) {
    }

    private static final Scale THOUSAND = new Scale("тысяча", "тысячи", "тысяч", true);
    private static final Scale MILLION = new Scale("миллион", "миллиона", "миллионов", false);
    private static final Scale BILLION = new Scale("миллиард", "миллиарда", "миллиардов", false);

    static String amountInWords(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        long whole = rounded.longValue();
        int tiyn = rounded.subtract(BigDecimal.valueOf(whole)).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).intValue();

        StringBuilder sb = new StringBuilder();
        appendGroup(sb, (whole / 1_000_000_000L) % 1000, BILLION);
        appendGroup(sb, (whole / 1_000_000L) % 1000, MILLION);
        appendGroup(sb, (whole / 1000L) % 1000, THOUSAND);
        appendGroup(sb, whole % 1000, null);

        String result = sb.toString().trim();
        if (result.isEmpty()) result = "ноль";
        result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        return result + " теңге " + String.format("%02d", tiyn) + " тиын";
    }

    private static void appendGroup(StringBuilder sb, long n, Scale scale) {
        if (n == 0) return;
        int hundreds = (int) (n / 100);
        int remainder = (int) (n % 100);

        if (hundreds > 0) sb.append(HUNDREDS[hundreds]).append(' ');

        if (remainder >= 10 && remainder < 20) {
            sb.append(TEENS[remainder - 10]).append(' ');
        } else {
            int tens = remainder / 10;
            int ones = remainder % 10;
            if (tens > 0) sb.append(TENS[tens]).append(' ');
            if (ones > 0) {
                boolean feminine = scale != null && scale.feminine();
                sb.append(feminine ? UNITS_F[ones] : UNITS_M[ones]).append(' ');
            }
        }

        if (scale != null) {
            sb.append(pluralForm(n, scale)).append(' ');
        }
    }

    private static String pluralForm(long n, Scale scale) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return scale.many();
        if (mod10 == 1) return scale.one();
        if (mod10 >= 2 && mod10 <= 4) return scale.few();
        return scale.many();
    }
}
