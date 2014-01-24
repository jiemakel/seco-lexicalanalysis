package fi.seco.lexical;

import java.util.HashMap;
import java.util.HashSet;

/**
 * This class attempts to recognize the language used in a text input.
 * 
 * @author joonas
 * 
 */
public class LanguageRecognizer {

	/**
	 * This type contains info about different alphabet sets. Each alphabet has
	 * a string containing every letter in the alphabet. Alphabets are defined
	 * by giving Unicode code point ranges that include the whole alphabet
	 * range.
	 * 
	 * @author jlaitio
	 * 
	 */
	private enum AlphabetEnum implements Alphabet {

		// Ex. in the latin alphabet, the code point ranges 
		// 0x0041-0x005A and 0x0061-0x007A are included (no punctuation) 
		latin("0x0041", "0x005A", "0x0061", "0x007A"),
		cyrillic("0x0400", "0x04FF"),
		greek("0x1F00", "0x1FFF");

		private final String charString;

		private AlphabetEnum(String... codePoints) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < codePoints.length / 2; i++) {
				String startPoint = codePoints[i * 2];
				String endPoint = codePoints[(i * 2) + 1];

				for (int j = Integer.decode(startPoint); j <= Integer.decode(endPoint); j++)
					sb.append((char) j);
			}

			this.charString = sb.toString();
		}

		@Override
		public String getCharstring() {
			return this.charString;
		}
	}

	/**
	 * This type contains info about common string occurrence in languages.
	 * Occurrences are divided into info about characters, words, sequences and
	 * endings, in this order.
	 * 
	 * Based loosely on the Wikipedia Language Recognition Chart (
	 * http://en.wikipedia.org/wiki/Language_recognition_chart , retrieved
	 * 06/2008 )
	 * 
	 * Characters outside the "Basic Latin" subset of UTF-8 are Unicode-encoded
	 * to prevent encoding issues when handling the code file.
	 * 
	 * @author jlaitio
	 * 
	 */
	private enum LangEnum implements Language {
		en(
				"",
				new String[] { "a", "an", "in", "on", "the", "that", "is", "are", "I" },
				new String[] { "th", "ch", "sh", "ough", "augh" },
				new String[] { "ing", "tion", "ed", "age", "s", "'s", "'ve", "n't", "'d" },
				AlphabetEnum.latin),

		fi(
				new String(new char[] { '\u00c4', '\u00d6', '\u00e4', '\u00f6' }),
				new String[] { "ja", "on", "ei" },
				new String[] { "ai", "uo", "ei", "ie", "oi", "aa", "ee", "ii", "kk", "ll", "ss", new String(new char[] { 'y', '\u00f6' }), new String(new char[] { '\u00e4', 'i' }) },
				new String[] { "nen", "ssa", "in", new String(new char[] { 'k', '\u00e4' }) },
				AlphabetEnum.latin),

		sv(
				new String(new char[] { '\u00c5', '\u00c4', '\u00d6', '\u00e5', '\u00e4', '\u00f6' }),
				new String[] { "och", "i", "att", "det", "en", "som", "av", "den", new String(new char[] { 'p', '\u00e5' }), new String(new char[] { '\u00e4', 'r' }) },
				new String[] { "stj", "sj", "skj", "tj" },
				new String[] {

				},
				AlphabetEnum.latin),

		de(
				new String(new char[] { '\u00c4', '\u00d6', '\u00dc', '\u00e4', '\u00f6', '\u00fc', '\u00df' }),
				new String[] { "der", "die", "das", "den", "dem", "des", "er", "sie", "es", "ist", "und", "oder", "aber" },
				new String[] { "sch", "tsch", "tz", "ss" },
				new String[] { "en", "er", "ern", "st", "ung", "chen" },
				AlphabetEnum.latin),

		fr(
				new String(new char[] { '\u00c0', '\u00c2', '\u00c7', '\u00c8', '\u00c9', '\u00ca', '\u00ce', '\u00d4', '\u00db', '\u00e0', '\u00e2', '\u00e7', '\u00e8', '\u00e9', '\u00ea', '\u00ee', '\u00f4', '\u00fb' }),
				new String[] { "de", "la", "le", "du", "des", "il", "et" },
				new String[] { "d'", "l'" },
				new String[] { "aux", "eux" },
				AlphabetEnum.latin),

		et(
				new String(new char[] { '\u00c4', '\u00d6', '\u00d5', '\u00dc', '\u00e4', '\u00f6', '\u00f5', '\u00fc' }),
				new String[] { "ja", "on", "ei", "ta", "see" },
				new String[] { "ai", "uo", "ei", "ie", "oi", "aa", "ee", "ii", "kk", "ll", "ss", new String(new char[] { 'y', '\u00f6' }), new String(new char[] { '\u00e4', 'i' }) },
				new String[] {
				//"d"           
				},
				AlphabetEnum.latin),

		it(
				new String(new char[] { '\u00e0', '\u00e9', '\u00e8', '\u00ec', '\u00f2', '\u00f9' }),
				new String[] { new String(new char[] { '\u00e9' }), new String(new char[] { 'p', 'e', 'r', 'c', 'h', '\u00e9' }) },
				new String[] { "gli", "gn", "sci", "tt", "zz", "cc", "ss", "bb", "pp", "ll" },
				new String[] { "zione", "mento", "aggio", "o", "a", "non", "il", "per", "con", new String(new char[] { 't', '\u00e0' }) },
				AlphabetEnum.latin),

		es(
				new String(new char[] { '\u00e1', '\u00e9', '\u00ed', '\u00f1', '\u00d1', '\u00f3', '\u00fa', '\u00fc', '\u00a1', '\u00bf' }),
				new String[] { "de", "el", "los", "las", "la", "uno", "y" },
				new String[] {

				},
				new String[] { "miento", "dad", new String(new char[] { 'c', 'i', '\u00f3', 'n' }) },
				AlphabetEnum.latin),

		pt(
				new String(new char[] { '\u00c1', '\u00c9', '\u00cd', '\u00d3', '\u00da', '\u00c2', '\u00ca', '\u00d4', '\u00c0', '\u00e3', '\u00f5', '\u00e7', '\u00e9', '\u00e8', '\u00ed', '\u00f3', '\u00fa', '\u00e2', '\u00ea', '\u00f4', '\u00e0', '\u00fc' }),
				new String[] { "a", "e", "o", "ao", "as", "da", "de", "do", new String(new char[] { '\u00e0' }), new String(new char[] { '\u00e9' }), new String(new char[] { '\u00e0', 's' }) },
				new String[] { "nh", "lh", "ct", new String(new char[] { 'c', '\u00e7' }) },
				new String[] { "dade", new String(new char[] { '\u00e7', '\u00e3', 'o' }), new String(new char[] { '\u00e7', '\u00f5', 'e', 's' }) },
				AlphabetEnum.latin),

		ru(new String(new char[] {}), new String[] {

		}, new String[] {

		}, new String[] {

		}, AlphabetEnum.cyrillic),

		pl(
				new String(new char[] { '\u0105', '\u0107', '\u0119', '\u0142', '\u0144', '\u00f3', '\u015b', '\u017a', '\u017c' }),
				new String[] { "i", "w" },
				new String[] { "rz", "sz", "cz", "prz", "trz" },
				new String[] { new String(new char[] { 's', 'i', '\u0119' }) },
				AlphabetEnum.latin),

		cs(
				new String(new char[] { '\u010e', '\u00c9', '\u011a', '\u0147', '\u00d3', '\u0158', '\u0164', '\u00da', '\u016e', '\u00dd', '\u00e1', '\u010f', '\u00e9', '\u011b', '\u0148', '\u00f3', '\u0159', '\u0165', '\u00fa', '\u016f', '\u00fd' }),
				new String[] { "je", "v" },
				new String[] {

				},
				new String[] {

				},
				AlphabetEnum.latin),

		sk(
				new String(new char[] { '\u00c4', '\u010e', '\u00c9', '\u00cd', '\u013d', '\u0139', '\u0147', '\u00d3', '\u00d4', '\u0154', '\u0164', '\u00da', '\u00dd', '\u00e1', '\u00e4', '\u010f', '\u00e9', '\u00ed', '\u013e', '\u013a', '\u0148', '\u00f3', '\u00f4', '\u0155', '\u0165', '\u00fa', '\u00fd' }),
				new String[] {

				},
				new String[] {

				},
				new String[] { "cia", new String(new char[] { '\u0165' }) },
				AlphabetEnum.latin),

		hu(
				new String(new char[] { '\u00c9', '\u00cd', '\u00d3', '\u00d6', '\u0150', '\u00da', '\u00dc', '\u0170', '\u00e1', '\u00e9', '\u00ed', '\u00f3', '\u00f6', '\u0151', '\u00fa', '\u00fc', '\u0171' }),
				new String[] { "a", "az", "ez", "egy", "van", new String(new char[] { '\u00e9', 's' }) },
				new String[] { "sz", "gy", "cs", "leg" },
				new String[] { "obb" },
				AlphabetEnum.latin),

		ro(
				new String(new char[] { '\u0102', '\u00ce', '\u00c2', '\u015e', '\u0162', '\u0103', '\u00ee', '\u00e2', '\u015f', '\u0163' }),
				new String[] { "si", "de", "la", "a", "ai", "ale", "alor", "cu" },
				new String[] { "ii", "iii" },
				new String[] { new String(new char[] { '\u0103' }), "ul", "ului", new String(new char[] { '\u0163', 'i', 'e' }), new String(new char[] { '\u0163', 'i', 'u', 'n', 'e' }), "ment", "tate" },
				AlphabetEnum.latin)
		/*
		 xx (new String(new char[] {}),
		    new String[] {
		    
		    },
		    new String[] { 
		                   
		    },
		    new String[] {
		    
		    },
		    AlphabetEnum.latin
		),
		 */
		;

		private final String langChars;
		private final String[] langWords;
		private final String[] langSequences;
		private final String[] langEndings;
		private final Alphabet alphabet;

		private LangEnum(String langChars, String[] langWords, String[] langSequences, String[] langEndings,
				Alphabet alphabet) {
			this.langChars = langChars;
			this.langWords = langWords;
			this.langSequences = langSequences;
			this.langEndings = langEndings;
			this.alphabet = alphabet;
		}

		@Override
		public String getChars() {
			return this.langChars;
		}

		@Override
		public String[] getWords() {
			return this.langWords;
		}

		@Override
		public String[] getSequences() {
			return this.langSequences;
		}

		@Override
		public String[] getEndings() {
			return this.langEndings;
		}

		@Override
		public String getAlphabet() {
			return this.alphabet.getCharstring();
		}
	}

	/**
	 * A common interface for the hard-coded enum implementation and for the
	 * data loaded from an external XML file
	 */
	private interface Language {
		String getChars();

		String[] getWords();

		String[] getSequences();

		String[] getEndings();

		String getAlphabet();

		String name();
	}

	private interface Alphabet {
		String getCharstring();

		String name();
	}

	/**
	 * A result object containing extra info about the language matching.
	 * 
	 */
	public interface Result {
		/**
		 * @return the ISO 639-1 language code of the recognized language.
		 */
		String getLang();

		double getMarginal();

		int getNumberOfWords();

		/**
		 * @return an approximate index on how accurate the recognition is. The
		 *         higher number, the more certain it is that the language is
		 *         correct.
		 */
		double getIndex();
	}

	/**
	 * Attempts to recognize the language used in the input string.
	 * 
	 * @param input
	 *            The text to be classified to a language
	 * @return ISO 639-1 language code of used language
	 */
	public static String getLanguage(String input) {
		Result result = getLanguageAsObject(input, (String[]) null);

		if (result != null) return result.getLang();

		return null;
	}

	/**
	 * Attempts to recognize the language used in the input string.
	 * 
	 * @param input
	 *            The text to be classified to a language
	 * @param wantedLanguages
	 *            ISO 639-1 language codes for the languages between which the
	 *            language should be classified, <code>null</code> or empty set
	 *            to use all available languages.
	 * @return ISO 639-1 language code of used language
	 */
	public static String getLanguage(String input, final String... wantedLanguages) {
		Result result = getLanguageAsObject(input, wantedLanguages);

		if (result != null) return result.getLang();

		return null;
	}

	/**
	 * Attempts to recognize the language used in the input string.
	 * 
	 * @param input
	 *            The text to be classified to a language
	 * @return A result-object containing the results
	 */
	public static Result getLanguageAsObject(String input) {
		return getLanguageAsObject(input, getLangList());
	}

	/**
	 * Attempts to recognize the language used in the input string.
	 * 
	 * @param input
	 *            The text to be classified to a language
	 * @param wantedLanguages
	 *            ISO 639-1 language codes for the languages between which the
	 *            language should be classified, <code>null</code> or empty set
	 *            to use all available languages.
	 * @return A result-object containing the results
	 */
	public static Result getLanguageAsObject(String input, final String... wantedLanguages) {
		return getLanguageAsObject(input, getLangList(), wantedLanguages);
	}

	public static String[] getAvailableLanguages() {
		Language[] langList = getLangList();

		String[] langs = new String[langList.length];

		for (int i = 0; i < langList.length; i++)
			langs[i] = langList[i].name();

		return langs;
	}

	private static Result getLanguageAsObject(String input, Language[] languages, final String... wantedLanguages) {
		if (input == null || input.trim().isEmpty()) return null;

		HashSet<String> langSet = null;
		if (wantedLanguages != null && wantedLanguages.length > 0) {
			langSet = new HashSet<String>();
			for (String lang : wantedLanguages)
				langSet.add(lang);
		}

		HashMap<String, Double> weights = initializeWeights(langSet, languages);
		HashMap<String, Language> languageMap = new HashMap<String, Language>();
		for (Language lang : languages)
			languageMap.put(lang.name(), lang);

		String[] row = input.split("[\\s\\d]+");
		removePunctuation(row);

		calculateChars(row, weights, languageMap);
		calculateWords(row, weights, languageMap);
		calculateSequences(row, weights, languageMap);
		calculateEndings(row, weights, languageMap);

		double highest = Double.NEGATIVE_INFINITY;
		double secondHighest = Double.NEGATIVE_INFINITY;
		String winnerLang = null;
		for (String lang : weights.keySet())
			if (weights.get(lang) > highest) {
				secondHighest = highest;
				highest = weights.get(lang);
				winnerLang = lang;
			}

		final double finalHighest = highest;
		final double finalSecondHighest = secondHighest;
		final String finalWinner = winnerLang;
		final int numberOfWords = row.length;
		final double index = calculateIndex((finalHighest - finalSecondHighest), numberOfWords);

		return new Result() {
			@Override
			public String getLang() {
				return finalWinner;
			}

			@Override
			public double getMarginal() {
				return (finalHighest - finalSecondHighest);
			}

			@Override
			public int getNumberOfWords() {
				return numberOfWords;
			}

			@Override
			public double getIndex() {
				return index;
			}
		};
	}

	private static HashMap<String, Double> initializeWeights(HashSet<String> wantedLanguages, Language[] languages) {
		HashMap<String, Double> weights = new HashMap<String, Double>();

		if (wantedLanguages == null || wantedLanguages.isEmpty())
			for (Language lang : languages)
				weights.put(lang.name(), 0.0d);
		else for (Language lang : languages)
			if (wantedLanguages.contains(lang.name())) weights.put(lang.name(), 0.0d);
		return weights;
	}

	private static Language[] getLangList() {
		return LangEnum.values();
	}

	private static String[] removePunctuation(String[] row) {
		char lastChar;
		for (int i = 0; i < row.length; i++)
			if (row[i].length() > 0) {
				lastChar = row[i].charAt(row[i].length() - 1);
				if (!Character.isLetter(lastChar)) row[i] = row[i].substring(0, row[i].length() - 1);
			}
		return row;
	}

	private static void calculateChars(String[] row, HashMap<String, Double> weights, HashMap<String, Language> langMap) {
		for (String word : row)
			for (String langString : weights.keySet()) {
				Language lang = langMap.get(langString);

				for (char character : word.toCharArray()) {
					String chars = lang.getChars();
					if (chars.indexOf(character) != -1) {
						double weightIncrement = Math.max(1.0 / chars.length(), 0.3);
						if (weightIncrement < 0.1) weightIncrement = 0.1;
						weights.put(lang.name(), weights.get(lang.name()) + weightIncrement);
					}
					if (lang.getAlphabet().concat(chars).indexOf(character) == -1 && Character.isLetter(character))
						weights.put(lang.name(), weights.get(lang.name()) - 1);
				}
			}
	}

	private static void calculateWords(String[] row, HashMap<String, Double> weights, HashMap<String, Language> langMap) {
		for (String word : row)
			for (String langString : weights.keySet()) {
				Language lang = langMap.get(langString);

				for (String langWord : lang.getWords())
					if (langWord.equals(word)) {
						double weightIncrement = Math.max(1.0 / lang.getWords().length, 0.3);
						if (weightIncrement < 0.1) weightIncrement = 0.1;
						weights.put(lang.name(), weights.get(lang.name()) + weightIncrement);
					}
			}
	}

	private static void calculateSequences(String[] row, HashMap<String, Double> weights,
			HashMap<String, Language> langMap) {
		for (String word : row)
			for (String langString : weights.keySet()) {
				Language lang = langMap.get(langString);

				for (String sequence : lang.getSequences())
					if (word.toLowerCase().contains(sequence)) {
						double weightIncrement = Math.max(1.0 / lang.getSequences().length, 0.3);
						if (weightIncrement < 0.1) weightIncrement = 0.1;
						weights.put(lang.name(), weights.get(lang.name()) + weightIncrement);
					}
			}
	}

	private static void calculateEndings(String[] row, HashMap<String, Double> weights,
			HashMap<String, Language> langMap) {
		for (String word : row)
			for (String langString : weights.keySet()) {
				Language lang = langMap.get(langString);

				for (String ending : lang.getEndings())
					if (word.toLowerCase().endsWith(ending)) {
						double weightIncrement = Math.max(1.0 / lang.getEndings().length, 0.3);
						if (weightIncrement < 0.1) weightIncrement = 0.1;
						weights.put(lang.name(), weights.get(lang.name()) + weightIncrement);
					}
			}
	}

	private static final double SCALE_CONSTANT = 4;
	private static final double ACCURACY_CONSTANT = 4;

	/**
	 * Normalizes the regocnition probability values to the range [0 1] using
	 * the standard logistic sigmoid function.
	 */
	private static double calculateIndex(double marginal, int numberOfWords) {
		double initial = marginal / numberOfWords;
		double accFactor = Math.pow(10, ACCURACY_CONSTANT);

		// Calculate the sigmoid to normalize
		double result = (1d / (1d + (Math.exp(initial * SCALE_CONSTANT * (-1)))));
		result = (result - 0.5) * 2;
		result = ((int) (result * accFactor)) / accFactor;
		return result;
	}

}