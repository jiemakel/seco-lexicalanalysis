package fi.seco.lexical.hfst;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fi.seco.hfst.Transducer;
import fi.seco.hfst.TransducerAlphabet;
import fi.seco.hfst.TransducerHeader;
import fi.seco.hfst.UnweightedTransducer;
import fi.seco.hfst.WeightedTransducer;
import fi.seco.lexical.ALexicalAnalysisService;
import fi.seco.lexical.LexicalAnalysisUtil;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;
import marmot.util.StringUtils;

public class HFSTLexicalAnalysisService extends ALexicalAnalysisService {
	private static final Logger log = LoggerFactory.getLogger(HFSTLexicalAnalysisService.class);

	protected final Map<Locale, Transducer> analysisTransducers = new HashMap<Locale, Transducer>();
	private final Map<Locale, Transducer> hyphenationTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> inflectionTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> guessTransducers = new HashMap<Locale, Transducer>();

	private final Collection<Locale> supportedAnalyzeLocales = new ArrayList<Locale>();
	private final Collection<Locale> supportedGuessLocales = new ArrayList<Locale>();
	private final Collection<Locale> supportedHyphenationLocales = new ArrayList<Locale>();
	protected final Collection<Locale> supportedInflectionLocales = new ArrayList<Locale>();

	public static class Result {

		private Map<String, List<String>> globalTags = new HashMap<String, List<String>>();

		public static class WordPart {
			private String lemma;
			private final Map<String, List<String>> tags = new HashMap<String, List<String>>();

			public WordPart() {}

			public WordPart(String lemma) {
				this.lemma = lemma;
			}

			public void setLemma(String lemma) {
				this.lemma = lemma;
			}

			public String getLemma() {
				return lemma;
			}

			public Map<String, List<String>> getTags() {
				return tags;
			}

			public void addTag(String key, String value) {
				if (!tags.containsKey(key)) tags.put(key, new ArrayList<String>());
				tags.get(key).add(value);
			}

			@Override
			public String toString() {
				return lemma + "(" + tags.toString() + ")";
			}
		}

		private final List<WordPart> wordParts = new ArrayList<WordPart>();

		@Override
		public String toString() {
			return wordParts.toString() + "&" + globalTags.toString() + ":" + weight;
		}

		private final float weight;

		public Result() {
			this.weight = 1.0f;
		}

		public Result(float weight) {
			this.weight = weight;
		}

		public float getWeight() {
			return weight;
		}

		public List<WordPart> getParts() {
			return wordParts;
		}

		public Result addPart(WordPart wp) {
			wordParts.add(wp);
			return this;
		}

		public Map<String, List<String>> getGlobalTags() {
			return globalTags;
		}

		public void setGlobalTags(Map<String, List<String>> globalTags) {
			this.globalTags = globalTags;
		}

		public void addGlobalTag(String key, String value) {
			if (!globalTags.containsKey(key)) globalTags.put(key, new ArrayList<String>());
			globalTags.get(key).add(value);
		}

	}

	public HFSTLexicalAnalysisService() {
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(HFSTLexicalAnalysisService.class.getResourceAsStream("analysis-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedAnalyzeLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no analysis/baseform languages");
		}
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(HFSTLexicalAnalysisService.class.getResourceAsStream("inflection-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedInflectionLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no inflection languages");
		}
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(HFSTLexicalAnalysisService.class.getResourceAsStream("hyphenation-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedHyphenationLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no hyphenation languages");
		}
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(HFSTLexicalAnalysisService.class.getResourceAsStream("guess-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedGuessLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no guessing languages");
		}
	}

	protected Transducer getTransducer(Locale l, String type, Map<Locale, Transducer> s) {
		Transducer t = s.get(l);
		if (t != null) return t;
		synchronized (this) {
			t = s.get(l);
			if (t != null) return t;
			String file = l.getLanguage() + "-" + type + ".hfst.ol";
			try {
				InputStream transducerfile = HFSTLexicalAnalysisService.class.getResourceAsStream(file);
				if (transducerfile == null) {
					log.error("Couldn't find transducer " + file);
					return null;
				}
				DataInputStream charstream = new DataInputStream(transducerfile);
				TransducerHeader h = new TransducerHeader(charstream);
				TransducerAlphabet a = new TransducerAlphabet(charstream, h.getSymbolCount());
				if (h.isWeighted())
					t = new WeightedTransducer(charstream, h, a);
				else t = new UnweightedTransducer(charstream, h, a);
				// t.analyze(""); // make sure transducer is synchronously initialized
			} catch (IOException e) {
				log.error("Couldn't initialize transducer " + file, e);
				return null;
			}
			s.put(l, t);
			return t;
		}
	}

	public static class WordToResults {
		private final String word;
		private final List<Result> analysis;

		public WordToResults(String word, List<Result> analysis) {
			this.word = word;
			this.analysis = analysis;
		}

		public String getWord() {
			return word;
		}

		public List<Result> getAnalysis() {
			return analysis;
		}

		@Override
		public String toString() {
			return word + ": " + analysis;
		}
	}
	
	protected static Result toResult(Transducer.Result tr) {
		Result r = new Result(tr.getWeight());
		final StringBuilder lemma = new StringBuilder();
		WordPart w = null;
		if (tr.getSymbols().get(0).startsWith("[")) { //[BOUNDARY=LEXITEM][LEMMA='san'][POS=NOUN][KTN=5][NUM=SG][CASE=NOM][BOUNDARY=COMPOUND][GUESS=COMPOUND][LEMMA='oma'][POS=ADJECTIVE][KTN=1%0][CMP=POS][NUM=SG][CASE=NOM][BOUNDARY=COMPOUND][GUESS=COMPOUND][LEMMA='lehti'][POS=NOUN][KTN=7][KAV=F][NUM=SG][CASE=PAR][ALLO=A][BOUNDARY=LEXITEM][CASECHANGE=NONE]
			boolean parsingSegment = false;
			boolean parsingTag = false;
			for (String s : tr.getSymbols()) {
				if (s.length() == 0) continue;
				if (s.charAt(0) == '[') {
					if (s.length() == 1) {
						parsingSegment = false;
						parsingTag = true;
					} else {
						String[] tmp = s.split("=");
						if ("[BOUNDARY".equals(tmp[0]) || "[WORD_ID".equals(tmp[0])) {
							parsingSegment = false;
							parsingTag = false;
							if (w == null)
								w = new WordPart();
							else if (w.getLemma() != null) {
								r.addPart(w);
								w = new WordPart();
							}
							lemma.setLength(0);
						} else if ("[SEGMENT".equals(tmp[0])) {
							parsingSegment = true;
							parsingTag = false;
							lemma.setLength(0);
						} else if (s.charAt(s.length() - 1) == ']') {
							parsingSegment = false;
							parsingTag = false;
							if (w == null) w = new WordPart();
							tmp=s.split("[=\\[\\]]");
							for (int i=0;i<tmp.length;i+=3)
								w.addTag(tmp[i+1], tmp[i+2]);									
						}
					}
				} else if (s.charAt(s.length() - 1) == ']') {
					if (parsingSegment)
						w.addTag("SEGMENT", lemma.toString());
					else if (parsingTag) {
						if (s.equals("]"))
							lemma.append('[');
						else {
							String[] tmp = lemma.toString().split("=");
							w.addTag(tmp[0], tmp[1]);
						}
					} else w.setLemma(lemma.toString());
					lemma.setLength(0);
					parsingSegment = false;
					parsingTag = false;
				} else lemma.append(s);
			}
			if (!w.getTags().isEmpty()) if (w.getLemma() != null)
				r.addPart(w);
			else r.setGlobalTags(w.getTags());
		} else { //sanomat#lehti N Par Sg 	write[V]+V+PROG 	writ[N]+ING[N/N]+N söka<verb><infinitiv><aktiv>
			w = new WordPart();
			boolean previousWasTag = false;
			for (String s : tr.getSymbols())
				if (s.length() == 0 || s.charAt(0) == '#' || s.charAt(0) == ':')
					previousWasTag = true;
				else if (s.charAt(0) == ' ') {
					previousWasTag = true;
					if (s.length() > 1) if (!r.getParts().isEmpty() && r.getParts().get(0).getTags().isEmpty())
						r.addGlobalTag(s.substring(1), s.substring(1));
					else w.addTag(s.substring(1), s.substring(1));
				} else if (s.charAt(0) == '+') {
					previousWasTag = true;
					if (s.length() > 1) if (!r.getParts().isEmpty() && r.getParts().get(0).getTags().isEmpty())
						r.addGlobalTag(s.substring(1), s.substring(1));
					else w.addTag(s.substring(1), s.substring(1));
				} else if (s.charAt(0) == '<' && s.charAt(s.length() - 1) == '>') {
					previousWasTag = true;
					if (!r.getParts().isEmpty() && r.getParts().get(0).getTags().isEmpty())
						r.addGlobalTag(s.substring(1, s.length() - 1), s.substring(1, s.length() - 1));
					else w.addTag(s.substring(1, s.length() - 1), s.substring(1, s.length() - 1));
				} else if (s.charAt(0) == '<' || s.charAt(0) == '>') {
					previousWasTag = true;
					if (s.length() > 1) if (!r.getParts().isEmpty() && r.getParts().get(0).getTags().isEmpty())
						r.addGlobalTag(s.substring(1), s.substring(1));
					else w.addTag(s.substring(1), s.substring(1));
				} else if (s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
					previousWasTag = true;
					if (!r.getParts().isEmpty() && r.getParts().get(0).getTags().isEmpty())
						r.addGlobalTag(s.substring(1, s.length() - 1), s.substring(1, s.length() - 1));
					else w.addTag(s.substring(1, s.length() - 1), s.substring(1, s.length() - 1));
				} else {
					if (previousWasTag) {
						if (lemma.length() > 0) {
							w.setLemma(lemma.toString());
							r.addPart(w);
						}
						w = new WordPart();
						lemma.setLength(0);
						previousWasTag = false;
					}
					lemma.append(s);
				}
			if (lemma.length() > 0) {
				w.setLemma(lemma.toString());
				r.addPart(w);
			}
			boolean hasTags = !r.getGlobalTags().isEmpty();
			if (!hasTags) for (WordPart wp : r.getParts())
				if (!wp.getTags().isEmpty()) {
					hasTags = true;
					break;
				}
			if (!hasTags && !r.getParts().isEmpty()) {
				Result r2 = new Result();
				WordPart fwp = r.getParts().get(0);
				for (int j = 1; j < r.getParts().size(); j++)
					fwp.addTag(r.getParts().get(j).getLemma(), r.getParts().get(j).getLemma());
				r2.addPart(fwp);
				r = r2;
			}
		}
		return r;
	}

	protected static List<Result> toResult(List<Transducer.Result> analysis) {
		List<Result> ret = new ArrayList<Result>(analysis.size());
		for (Transducer.Result tr : analysis) {
			if (tr.getSymbols().isEmpty()) continue;
			ret.add(toResult(tr));
		}
		return ret;
	}

	public double recognize(String str, Locale lang) {
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		int recognized = 0;
		String[] labels = LexicalAnalysisUtil.split(str);
		outer: for (String label : labels)
			for (Transducer.Result tr : tc.analyze(label))
				if (!tr.getSymbols().isEmpty() && !tr.getSymbols().contains("»") && !tr.getSymbols().contains("[POS=PUNCT]") && !tr.getSymbols().contains("PUNCT") && !tr.getSymbols().contains("+PUNCT")) {
					recognized++;
					continue outer;
				}
		return ((double) recognized) / labels.length;
	}

	public List<WordToResults> analyze(String str, Locale lang, List<String> inflections, boolean segments) {
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		String[] labels = LexicalAnalysisUtil.split(str);
		List<WordToResults> ret = new ArrayList<WordToResults>(labels.length);
		StringBuilder cur = new StringBuilder();
		for (String label : labels)
			if (!"".equals(label)) {
				List<Result> r = toResult(tc.analyze(label));
				if (r.isEmpty() && supportedGuessLocales.contains(lang)) {
					Transducer tc2 = getTransducer(lang,"guess",guessTransducers);
					String reversedLabel = StringUtils.reverse(label);
					List<Transducer.Result> analysis = Collections.EMPTY_LIST;
					int length = reversedLabel.length();
					while (analysis.isEmpty() && length>0)
						analysis = tc2.analyze(reversedLabel.substring(0,length--));
					if (!analysis.isEmpty()) {
						float cw = Float.MAX_VALUE;
						Transducer.Result bestResult = null;
						for (Transducer.Result res : analysis) {
							if (res.getWeight() < cw) {
								bestResult = res;
								cw = res.getWeight();
							}
						}
						Collections.reverse(bestResult.getSymbols());
						if (!bestResult.getSymbols().get(0).startsWith("[")) bestResult.getSymbols().add(0,"[WORD_ID=");
						Result gr = toResult(bestResult);
						gr.getParts().get(0).setLemma(label.substring(0,label.length()-length-1)+gr.getParts().get(0).getLemma());
						List<String> gsegments = gr.getParts().get(0).getTags().get("SEGMENT");
						if (gsegments!=null) {
							List<String> nsegments = new ArrayList<String>();
							int clindex = label.length()-1;
							for (int i=gsegments.size()-1;i>=0;i--) {
								String cs = gsegments.get(i).replace("»", "").replace("{WB}", "#").replace("{XB}", "").replace("{DB}", "").replace("{MB}", "").replace("{STUB}", "").replace("{hyph?}", "");
								int cindex = cs.length()-1; 
								while (cindex>=0 && clindex>=0 && label.charAt(clindex--)==cs.charAt(cindex--));
								if (cindex!=-1) {
									nsegments.add(label.substring(0,clindex+2) + gsegments.get(i).substring(cindex+2));
									clindex=-1;
									break;
								} else nsegments.add(gsegments.get(i));
							}
							Collections.reverse(nsegments);
							if (clindex!=-1) nsegments.set(0,label.substring(0,clindex+1)+nsegments.get(0));
							gr.getParts().get(0).getTags().put("SEGMENT", nsegments);
						}
						gr.addGlobalTag("GUESSED", "TRUE");
						r.add(gr);
					}
				}
				if (r.isEmpty()) r.add(new Result().addPart(new WordPart(label)));
				Result bestResult = null;
				float cw = Float.MAX_VALUE;
				for (Result res : r) {
					if (res.getWeight() < cw) {
						bestResult = res;
						cw = res.getWeight();
					}
				}
				bestResult.addGlobalTag("BEST_MATCH", "TRUE");
				if (segments)
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<WordToResults> analysis = analyze(wp.getLemma(), lang, Collections.EMPTY_LIST, false);
							if (analysis.size()==0)
								continue;
							Result br = getBestResult(analysis.get(0));
							List<String> bwpSegments = new ArrayList<String>();
							for (WordPart bwp : br.getParts()) {
								if (bwp.getTags().containsKey("SEGMENT"))
									bwpSegments.addAll(bwp.getTags().get("SEGMENT"));
								else bwpSegments.add(bwp.getLemma());
								bwpSegments.add("{WB}");
							}
							bwpSegments.remove(bwpSegments.size()-1);
							wp.getTags().put("BASEFORM_SEGMENT",bwpSegments);
						}
				if (!inflections.isEmpty() && supportedInflectionLocales.contains(lang)) {
					Transducer tic = getTransducer(lang, "inflection", inflectionTransducers);
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<String> inflectedC = new ArrayList<String>();
							List<String> inflectedFormC = new ArrayList<String>();
							for (String inflection : inflections) {
								String inflected = firstToString(tic.analyze(wp.getLemma() + " " + inflection));
								if (!inflected.isEmpty()) {
									inflectedC.add(inflected);
									inflectedFormC.add(inflection);
								}
							}
							if (!inflectedC.isEmpty()) {
								wp.getTags().put("INFLECTED", inflectedC);
								wp.getTags().put("INFLECTED_FORM", inflectedFormC);
							}
						}
				}
				ret.add(new WordToResults(label, r));
			}
		return ret;
	}
	
	protected Result getBestResult(WordToResults cr) {
		float cw = Float.MAX_VALUE;
		Result ret = null;
		for (Result r : cr.getAnalysis())
			if (r.getWeight() < cw) ret=r;
		return ret;
	}

	protected String getBestLemma(WordToResults cr, Locale lang, boolean segments) {
		float cw = Float.MAX_VALUE;
		StringBuilder cur = new StringBuilder();
		for (Result r : cr.getAnalysis())
			if (r.getWeight() < cw) {
				cur.setLength(0);
				for (WordPart wp : r.getParts())
					if (segments) {
						if (wp.getTags().containsKey("BASEFORM_SEGMENT")) for (String s : wp.getTags().get("BASEFORM_SEGMENT")) 
							if (!"-0".equals(s)) 
								cur.append(s.replace("»", "").replace("{WB}", "#").replace("{XB}", "").replace("{DB}", "").replace("{MB}", "").replace("{STUB}", "").replace("{hyph?}", ""));
						cur.append('#');
					}
					else cur.append(wp.getLemma());
				if (segments && cur.length()>0) cur.setLength(cur.length()-1);
				cw = r.getWeight();
			}
		return cur.toString();
	}

	@Override
	public String baseform(String string, Locale lang, boolean segments) {
		try {
			List<WordToResults> crc = analyze(string, lang, Collections.EMPTY_LIST, segments);
			StringBuilder ret = new StringBuilder();
			for (WordToResults cr : crc) {
				ret.append(getBestLemma(cr, lang, segments));
				ret.append(' ');
			}
			return ret.toString().trim();
		} catch (ArrayIndexOutOfBoundsException e) {
			return string;
		}

	}

	@Override
	public Collection<Locale> getSupportedBaseformLocales() {
		return supportedAnalyzeLocales;
	}

	public Collection<Locale> getSupportedAnalyzeLocales() {
		return supportedAnalyzeLocales;
	}

	protected String firstToString(List<Transducer.Result> rl) {
		StringBuilder sb = new StringBuilder();
		for (Transducer.Result r : rl) {
			for (String s : r.getSymbols())
				sb.append(s);
			if (sb.length() > 0) return sb.toString();
		}
		return "";
	}

	@Override
	public String hyphenate(String string, Locale lang) {
		Transducer tc = getTransducer(lang, "hyphenation", hyphenationTransducers);
		String[] labels = LexicalAnalysisUtil.split(string);
		StringBuilder ret = new StringBuilder();
		for (String label : labels)
			if (!"".equals(label)) {
				String r = firstToString(tc.analyze(label));
				if (r.isEmpty()) r = firstToString(tc.analyze(label.toLowerCase()));
				if (r.isEmpty()) r = label;
				if (r.charAt(r.length() - 1) == '-' || r.charAt(r.length() - 1) == '^')
					ret.append(r.substring(0, r.length() - 1));
				else ret.append(r);
				ret.append(' ');
			}
		if (ret.length() > 0) ret.setLength(ret.length() - 1);
		return ret.toString();
	}

	@Override
	public Collection<Locale> getSupportedHyphenationLocales() {
		return supportedHyphenationLocales;
	}

	@Override
	public String inflect(String string, List<String> inflections, boolean segments, boolean baseform, Locale lang) {
		StringBuilder ret = new StringBuilder();
		for (WordToResults part : analyze(string, lang, inflections, false)) {
			String inflected = getBestInflection(part, lang, segments, baseform);
			if (!inflected.isEmpty())
				ret.append(inflected);
			else ret.append(part.getWord());
			ret.append(' ');
		}
		return ret.toString().trim();
	}

	protected String getBestInflection(WordToResults cr, Locale lang, boolean segments, boolean baseform) {
		float cw = Float.MAX_VALUE;
		StringBuilder cur = new StringBuilder();
		boolean foundInflection = baseform;
		for (Result r : cr.getAnalysis())
			if (r.getWeight() < cw) {
				cw = r.getWeight();
				cur.setLength(0);
				for (int i = 0; i < r.getParts().size() - 1; i++) {
					WordPart wp = r.getParts().get(i);
					List<String> isegments = wp.getTags().get("SEGMENT");
					if (isegments != null) for (String s : isegments)
						if (!"-0".equals(s)) {
							if (segments) {
								cur.append(s.replace("»", "").replace("{WB}", "#").replace("{XB}", "").replace("{DB}", "").replace("{MB}", "").replace("{STUB}", "").replace("{hyph?}", ""));
								cur.append('#');
							} else 
								cur.append(s.replace("»", "").replace("{WB}", "").replace("{XB}", "").replace("{DB}", "").replace("{MB}", "").replace("{STUB}", "").replace("{hyph?}", ""));
						} else cur.append(wp.getLemma());
					if (segments && cur.length()>0) cur.setLength(cur.length()-1);
				}
				WordPart wp = r.getParts().get(r.getParts().size() - 1);
				if (wp.getTags().get("INFLECTED") != null) {
					cur.append(wp.getTags().get("INFLECTED").get(0));
					foundInflection = true;
				} else cur.append(wp.getLemma());
			}
		if (!foundInflection) cur.setLength(0);
		return cur.toString();
	}

	@Override
	public Collection<Locale> getSupportedInflectionLocales() {
		return supportedInflectionLocales;
	}
	
	public static void main(String[] args) throws Exception {
		final HFSTLexicalAnalysisService hfst = new HFSTLexicalAnalysisService();
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy dog", new Locale("mdf")));
		System.out.println(hfst.analyze("tliittasin",new Locale("fi"),Collections.EMPTY_LIST,false));
		System.out.println(hfst.analyze("tliikkasin",new Locale("fi"),Collections.EMPTY_LIST,false));
		System.out.println(hfst.analyze("twiittasin",new Locale("fi"),Collections.EMPTY_LIST,false));
		System.out.println(hfst.analyze("635",new Locale("fi"),Collections.EMPTY_LIST,true));
		System.out.println(hfst.baseform("ulkoasiainministeriövaa'at soitti fagottia", new Locale("fi"),true));
		System.out.println(hfst.analyze("ulkoasiainministeriövaa'at 635. 635 sanomalehteä luin Suomessa", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true));
		System.out.println(hfst.baseform("635. 635 Helsingissä ulkoasiainministeriöstä vastaukset sanomalehdet varusteet komentosillat tietokannat tulosteet kriisipuhelimet kuin hyllyt", new Locale("fi"),true));
		System.out.println(hfst.hyphenate("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(hfst.recognize("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("la")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("de")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("myv")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("en")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("mrj")));		
		System.out.println(hfst.recognize("Eorum una, pars, quam Gallos obtinere dictum est, initium capit a flumine Rhodano, continetur Garumna flumine, Oceano, finibus Belgarum, attingit etiam ab Sequanis et Helvetiis flumen Rhenum, vergit ad septentriones.", new Locale("la")));
		System.out.println(hfst.inflect("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true, true, new Locale("fi")));
		System.out.println(hfst.inflect("maatiaiskanan sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), false, false, new Locale("fi")));
		//System.out.println(fdg.baseform("Otin 007 hiusta mukaan, mutta ne menivät kuuseen foobar!@£$£‰£@$ leileipä,. z.ajxc ha dsjf,mac ,mh ", new Locale("fi")));
		//System.out.println(fdg.analyze("Joukahaisen mierolla kuin tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		//System.out.println(fdg.baseform("Joukahaisen mierolla kuin tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		//System.out.println(fdg.baseform("johdanto Hyvin toimiva sosiaalihuolto ja siihen liittyvä palvelujärjestelmä ovat keskeinen osa ihmisten hyvinvoinnin ja perusoikeuksien toteuttamista. Sosiaalihuollon järjestämisen ja yksilönsosiaalisten oikeuksien toteutumisen perusta on perustuslain 19 §:ssä. Se turvaa jokaiselleoikeuden välttämättömään toimeentuloon ja huolenpitoon", new Locale("fi")));
	}


}
