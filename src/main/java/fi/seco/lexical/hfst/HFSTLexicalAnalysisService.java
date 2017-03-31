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

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.procedures.ObjectIntProcedure;

import fi.seco.hfst.Transducer;
import fi.seco.hfst.TransducerAlphabet;
import fi.seco.hfst.TransducerHeader;
import fi.seco.hfst.UnweightedTransducer;
import fi.seco.hfst.WeightedTransducer;
import fi.seco.lexical.ALexicalAnalysisService;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;
import marmot.util.StringUtils;

public class HFSTLexicalAnalysisService extends ALexicalAnalysisService {
	private static final Logger log = LoggerFactory.getLogger(HFSTLexicalAnalysisService.class);

	protected final Map<Locale, Transducer> analysisTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, char[]> alphabets = new HashMap<Locale, char[]>();
	private final Map<Locale, Transducer> hyphenationTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> inflectionTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> guessTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> guessSegmentTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> fuzzyTransducers = new HashMap<Locale, Transducer>();
	protected final Map<Locale, Transducer> fuzzySegmentTransducers = new HashMap<Locale, Transducer>();

	private final Collection<Locale> supportedAnalyzeLocales = new ArrayList<Locale>();
	private final Collection<Locale> supportedGuessLocales = new ArrayList<Locale>();
	private final Collection<Locale> supportedHyphenationLocales = new ArrayList<Locale>();
	private final Collection<Locale> supportedFuzzyLocales = new ArrayList<Locale>();
	protected final Collection<Locale> supportedInflectionLocales = new ArrayList<Locale>();
	
	private final Map<Locale, String[]> inflectionTags = new HashMap<Locale, String[]>();
	
	protected List<String> getEditDistance(Locale l, String string, int distance) {
		List<String> ret = new ArrayList<String>();
		getEditDistance(l,ret,"",string,distance);
		return ret;
	}
	
	protected void getEditDistance(Locale l, List<String> ret, String prefix, String string, int distance) {
		StringBuilder sb = new StringBuilder();
		for (int i=string.length()-1;i>=0;i--) {
			sb.setLength(0);
			sb.append(prefix);
			sb.append(string.substring(0,i));
			if (distance>1)
				getEditDistance(l,ret,sb.toString(),string.substring(i+1),distance-1);
			else {
				sb.append(string.substring(i+1));
				ret.add(sb.toString());
			}
		}
	}

	public static class Result {

		private Map<String, List<String>> globalTags = new HashMap<String, List<String>>();
		
		public int hashCode() {
			return globalTags.hashCode() + 31 * wordParts.hashCode() + 37 * Float.floatToIntBits(weight);
		};
		
		@Override
		public boolean equals(Object obj) {
			Result o = (Result)obj;
			return o.globalTags.equals(globalTags) && o.wordParts.equals(wordParts) && o.weight==weight;
		}

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
			
			@Override
			public int hashCode() {
				return tags.hashCode()+31*lemma.hashCode();
			}
			
			@Override
			public boolean equals(Object obj) {
				WordPart o = (WordPart)obj;
				return o.tags.equals(tags) && o.lemma.equals(lemma);
			}
		}

		private final List<WordPart> wordParts = new ArrayList<WordPart>();

		@Override
		public String toString() {
			return wordParts.toString() + "&" + globalTags.toString() + ":" + weight;
		}

		private float weight;
		
		public void setWeight(float weight) {
			this.weight=weight;
		}

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

		public Result addGlobalTag(String key, String value) {
			if (!globalTags.containsKey(key)) globalTags.put(key, new ArrayList<String>());
			globalTags.get(key).add(value);
			return this;
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
			while ((line = r.readLine()) != null) {
				String[] parts = line.split(":",-1);
				supportedInflectionLocales.add(new Locale(parts[0]));
				inflectionTags.put(new Locale(parts[0]), new String[] {parts[1], parts[2], parts[3]});
			}
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
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(HFSTLexicalAnalysisService.class.getResourceAsStream("fuzzy-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedFuzzyLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no fuzzy languages");
		}
	}
	
	protected char[] getAlphabet(Locale l) {
		char[] alphabet = alphabets.get(l);
		if (alphabet!=null) return alphabet;
		synchronized (this) {
			alphabet = alphabets.get(l);
			if (alphabet!=null) return alphabet;
			List<String> ta = getTransducer(l, "analysis", analysisTransducers).getAlphabet();
		    List<Character> taf = new ArrayList<Character>();
			for (String t : ta) if (t.length()==1) taf.add(t.charAt(0));
			alphabet = new char[taf.size()];
			for (int i=0;i<alphabet.length;i++) alphabet[i]=taf.get(i);
			alphabets.put(l,alphabet);
			return alphabet;
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
			String parsingPartialTag = null;
			boolean parsingTag = false;
			boolean lastWasLemmaStart = false;
			for (String s : tr.getSymbols()) {
				if (s.length() == 0) continue;
				if (s.charAt(0) == '[' && !lastWasLemmaStart) {
					if (s.length() == 1) {
						parsingPartialTag = null;
						parsingTag = true;
					} else {
						String[] tmp = s.split("=");
						if ("[WORD_ID".equals(tmp[0]))
							lastWasLemmaStart = true;
						if ("[BOUNDARY".equals(tmp[0]) || lastWasLemmaStart) {
							parsingPartialTag = null;
							parsingTag = false;
							if (w == null)
								w = new WordPart();
							else if (w.getLemma() != null) {
								r.addPart(w);
								w = new WordPart();
							}
							lemma.setLength(0);
						} else if (s.charAt(s.length() - 1) == ']') {
							parsingPartialTag = null;
							lastWasLemmaStart = false;
							parsingTag = false;
							if (w == null) w = new WordPart();
							tmp=s.split("[=\\[\\]]");
							for (int i=0;i<tmp.length;i+=3)
								w.addTag(tmp[i+1], tmp[i+2]);									
						} else {
							if (tmp[0].length() > 0 && tmp[0].charAt(0) == '[')
								parsingPartialTag = tmp[0].substring(1);
							else parsingPartialTag = tmp[0];
							parsingTag = false;
							lastWasLemmaStart = true;
							lemma.setLength(0);
							if (tmp.length==2) lemma.append(tmp[1]);
						}
					}
				} else if (s.charAt(s.length() - 1) == ']' && !lastWasLemmaStart) {
					lastWasLemmaStart = false;
					if (parsingPartialTag != null) {
						if (w==null) w = new WordPart();
						w.addTag(parsingPartialTag, lemma.toString());
					} else if (parsingTag) {
						if (s.equals("]"))
							lemma.append('[');
						else {
							String[] tmp = lemma.toString().split("=");
							w.addTag(tmp[0], tmp[1]);
						}
					} else w.setLemma(lemma.toString());
					lemma.setLength(0);
					parsingPartialTag = null;
					parsingTag = false;
				} else {
					lastWasLemmaStart = false;
					lemma.append(s);
				}
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
	
	public static class RecognitionResult {
		private final int recognized;
		private final int unrecognized;
		private final double rate;
		public RecognitionResult(int recognized, int unrecognized) {
			this.recognized = recognized;
			this.unrecognized = unrecognized;
			int total = recognized+unrecognized;
			if (total==0) this.rate=0;
			else this.rate = ((double)recognized)/total;
		}
		public int getRecognized() {
			return recognized;
		}
		public int getUnrecognized() {
			return unrecognized;
		}
		public double getRate() {
			return rate;
		}
		
	}

	public RecognitionResult recognize(String str, Locale lang) {
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		int recognized = 0;
		int unrecognized = 0;
		Collection<String> labels = tokenize(str, lang);
		outer: for (String label : labels) {
			for (Transducer.Result tr : tc.analyze(label))
				if (!tr.getSymbols().isEmpty()) {
					recognized++;
					continue outer;
				}
			unrecognized++;
		}
		return new RecognitionResult(recognized,unrecognized);
	}

	public List<WordToResults> analyze(String str, Locale lang, List<String> inflections, boolean segmentBaseform, boolean guessUnknown, boolean segmentUnknown, int maxErrorCorrectDistance) {
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		Collection<String> labels = tokenize(str,lang);
		List<WordToResults> ret = new ArrayList<WordToResults>(labels.size());
		for (String label : labels)
			if (!"".equals(label)) {
				final List<Result> r = toResult(tc.analyze(label));
				if (r.isEmpty() && maxErrorCorrectDistance>0 && supportedFuzzyLocales.contains(lang) ) {
					Transducer tc2 = segmentUnknown ? getTransducer(lang,"analysis-fuzzy-segment",fuzzySegmentTransducers) : getTransducer(lang,"analysis-fuzzy",fuzzyTransducers);
					for (int j=1;j<=maxErrorCorrectDistance;j++) {
						for (String c : getEditDistance(lang, label,j)) {
							List<Transducer.Result> res2 = tc2.analyze(c);
							for (Transducer.Result r2: res2)
								if (r2.getWeight()<(j+1)*1000)
									r.add(toResult(r2).addGlobalTag("EDIT_DISTANCE", ""+j));
						}
						if (!r.isEmpty()) break;
					}
				}
				if (r.isEmpty() && guessUnknown && supportedGuessLocales.contains(lang) && label.length()>=4) { // Fixed cutoff, don't guess words shorter than 4 chars.
					Transducer tc2 = segmentUnknown ? getTransducer(lang,"analysis-guess-segment",guessSegmentTransducers) : getTransducer(lang,"analysis-guess",guessTransducers);
					String reversedLabel = StringUtils.reverse(label);
					List<Transducer.Result> analysis = Collections.EMPTY_LIST;
					int length = reversedLabel.length();
					while (analysis.isEmpty() && length>3) // Fixed cutoff of min 3 last chars to use 
						analysis = tc2.analyze(reversedLabel.substring(0,length--));
					if (!analysis.isEmpty()) {
						for (Transducer.Result tr: analysis) {
							Collections.reverse(tr.getSymbols());
							if (!tr.getSymbols().get(0).startsWith("[")) tr.getSymbols().add(0,"[WORD_ID=");
						}
						ObjectIntHashMap<Result> gres = new ObjectIntHashMap<Result>();
						outer: for (Result gr : toResult(analysis)) {
							if (gr.getParts().isEmpty()) continue;
							boolean empty = true;
							for (WordPart p : gr.getParts()) if (!"".equals(p.getLemma())) {
								empty=false;
								break;
							}
							if (empty) continue;
							gr.getParts().get(0).getTags().remove("GUESS_CATEGORY");
							gr.getParts().get(0).getTags().remove("KAV");
							gr.getParts().get(0).getTags().remove("PROPER");
							gr.getParts().get(0).getTags().remove("SEM");
							List<String> pos = gr.getParts().get(0).getTags().get("UPOS");
							if (pos!=null) for (int j=0;j<pos.size();j++)
								if (pos.get(j).equals("PROPN")) pos.set(j,"NOUN");
							gr.getParts().get(0).setLemma(label.substring(0,label.length()-length-1)+gr.getParts().get(0).getLemma());
							List<String> gsegments = gr.getParts().get(0).getTags().get("SEGMENT");
							if (gsegments!=null) {
								List<String> nsegments = new ArrayList<String>();
								int clindex = label.length()-1;
								for (int j=gsegments.size()-1;j>=0;j--) {
									String cs = gsegments.get(j);
									if (cs.contains("{WB}")) continue outer;
									int cindex = cs.length()-1; 
									while (cindex>=0 && clindex>=0) {
										if (cs.charAt(cindex)=='»') cindex--;
										else {
											String tmp = cs.substring(0,cindex+1);
											if (tmp.endsWith("{WB}") || tmp.endsWith("{XB}") || tmp.endsWith("{DB}") || tmp.endsWith("{MB}")) cindex-=4;
											else if (tmp.endsWith("{STUB}")) cindex-=6;
											else if (tmp.endsWith("{hyph?}")) cindex-=7;
											else if (label.charAt(clindex--)!=cs.charAt(cindex--)) break;
										}
									}
									if (cindex!=-1) {
										nsegments.add(label.substring(0,clindex+2) + cs.substring(cindex+2));
										clindex=-1;
										break;
									} else nsegments.add(gsegments.get(j));
									if (j!=0 && clindex==-1) continue outer;
								}
								Collections.reverse(nsegments);
								if (clindex!=-1) nsegments.set(0,label.substring(0,clindex+1)+nsegments.get(0));
								gr.getParts().get(0).getTags().put("SEGMENT", nsegments);
							}
							gres.putOrAdd(gr, 1, 1);
						}
						gres.forEach(new ObjectIntProcedure<Result>() {
							public void apply(Result value, int v2) { value.setWeight(value.getWeight()/v2);value.addGlobalTag("GUESS_COUNT",""+v2); r.add(value); };
						});
					}
				}
				if (r.isEmpty())
					r.add(new Result().addGlobalTag("UNKNOWN", "TRUE").addPart(new WordPart(label)));
				List<Result> bestResult = new ArrayList<Result>();
				float cw = Float.MAX_VALUE;
				for (Result res : r) {
					if (res.getWeight() < cw) {
						bestResult.clear();
						bestResult.add(res);
						cw = res.getWeight();
					} else if (res.getWeight() == cw) bestResult.add(res);
				}
				for (Result res: bestResult)
					res.addGlobalTag("BEST_MATCH", "TRUE");
				if (segmentBaseform)
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<WordToResults> analysis = analyze(wp.getLemma(), lang, Collections.EMPTY_LIST, false, guessUnknown, true,maxErrorCorrectDistance);
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
					String[] tagdelims = inflectionTags.get(lang);
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<String> inflectedC = new ArrayList<String>();
							List<String> inflectedFormC = new ArrayList<String>();
							for (String inflection : inflections) {
								String inflected = firstToString(tic.analyze(wp.getLemma() + tagdelims[0] + inflection.replace(" ",tagdelims[2]+tagdelims[1])+ tagdelims[2]));
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
		Result ret = null;
		for (Result r : cr.getAnalysis())
			if (r.getGlobalTags().containsKey("BEST_MATCH")) ret=r;
		return ret;
	}

	protected String getBestLemma(WordToResults cr, Locale lang, boolean segments) {
		StringBuilder cur = new StringBuilder();
		for (Result r : cr.getAnalysis())
			if (r.getGlobalTags().containsKey("BEST_MATCH")) {
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
			}
		return cur.toString();
	}

	@Override
	public String baseform(String string, Locale lang, boolean segments, boolean guessUnknown, int maxErrorCorrectDistance) {
		try {
			List<WordToResults> crc = analyze(string, lang, Collections.EMPTY_LIST, segments, guessUnknown, false, maxErrorCorrectDistance);
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
		Collection<String> labels = tokenize(string, lang);
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
	public String inflect(String string, List<String> inflections, boolean segments, boolean baseform, boolean guessUnknown, int maxErrorCorrectDistance, Locale lang) {
		StringBuilder ret = new StringBuilder();
		for (WordToResults part : analyze(string, lang, inflections, false, guessUnknown, false, maxErrorCorrectDistance)) {
			String inflected = getBestInflection(part, lang, segments, baseform);
			if (!inflected.isEmpty())
				ret.append(inflected);
			else ret.append(part.getWord());
			ret.append(' ');
		}
		return ret.toString().trim();
	}

	protected String getBestInflection(WordToResults cr, Locale lang, boolean segments, boolean baseform) {
		StringBuilder cur = new StringBuilder();
		boolean foundInflection = baseform;
		for (Result r : cr.getAnalysis())
			if (r.getGlobalTags().containsKey("BEST_MATCH")) {
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
	
	protected static void print(List<WordToResults> res) {
		for (WordToResults wtr : res)
			for (Result r: wtr.getAnalysis())
				System.out.println(wtr.getWord()+":"+r.getWeight()+"->"+r.getGlobalTags()+"/"+r.getParts());
	}
	
	public static void main(String[] args) throws Exception {
		final HFSTLexicalAnalysisService hfst = new HFSTLexicalAnalysisService();
		System.out.println(hfst.inflect("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true, true, true, 0, new Locale("fi")));
		System.out.println(hfst.inflect("maatiaiskanan sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), false, false, false, 0, new Locale("fi")));
		System.exit(0);
		//		System.out.println(hfst.analyze("tliittasin",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0));
//		System.out.println(hfst.analyze("tliittasin",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,1));
		print(hfst.analyze("juoksettumise!sa",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.out.println(hfst.baseform("juoksettumise!sa", new Locale("fi"),false,false,0));
		System.out.println(hfst.baseform("juoksettumise!sa", new Locale("fi"),false,false,1));
		System.out.println(hfst.baseform("juoksettumise!sa", new Locale("fi"),false,false,1));
		System.out.println(hfst.baseform("juoksettumise!sa", new Locale("fi"),false,false,2));
		print(hfst.analyze("juoksettumise!sa",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,1));
		print(hfst.analyze("sanomalehtzä",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,1));
		print(hfst.analyze("sanomaleh!zä",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy dog", new Locale("mdf")));
		System.out.println(hfst.analyze("tliikkasin",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0));
		System.out.println(hfst.analyze("twiittasin",new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0));
		System.out.println(hfst.analyze("635",new Locale("fi"),Collections.EMPTY_LIST,true,true,true,0));
		System.out.println(hfst.baseform("ulkoasiainministeriövaa'at soitti fagottia", new Locale("fi"),true,true,0));
		System.out.println(hfst.analyze("ulkoasiainministeriövaa'at 635. 635 sanomalehteä luin Suomessa", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true,true,true,0));
		System.out.println(hfst.baseform("635. 635 Helsingissä ulkoasiainministeriöstä vastaukset sanomalehdet varusteet komentosillat tietokannat tulosteet kriisipuhelimet kuin hyllyt", new Locale("fi"),true,true,0));
		System.out.println(hfst.hyphenate("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(hfst.recognize("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("la")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("de")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("myv")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("en")));
		System.out.println(hfst.recognize("The quick brown fox jumps over the lazy cat", new Locale("mrj")));		
		System.out.println(hfst.recognize("Eorum una, pars, quam Gallos obtinere dictum est, initium capit a flumine Rhodano, continetur Garumna flumine, Oceano, finibus Belgarum, attingit etiam ab Sequanis et Helvetiis flumen Rhenum, vergit ad septentriones.", new Locale("la")));
		System.out.println(hfst.inflect("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true, true, true, 0, new Locale("fi")));
		System.out.println(hfst.inflect("maatiaiskanan sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), false, false, false, 0, new Locale("fi")));
		//System.out.println(fdg.baseform("Otin 007 hiusta mukaan, mutta ne menivät kuuseen foobar!@£$£‰£@$ leileipä,. z.ajxc ha dsjf,mac ,mh ", new Locale("fi")));
		//System.out.println(fdg.analyze("Joukahaisen mierolla kuin tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		//System.out.println(fdg.baseform("Joukahaisen mierolla kuin tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		//System.out.println(fdg.baseform("johdanto Hyvin toimiva sosiaalihuolto ja siihen liittyvä palvelujärjestelmä ovat keskeinen osa ihmisten hyvinvoinnin ja perusoikeuksien toteuttamista. Sosiaalihuollon järjestämisen ja yksilönsosiaalisten oikeuksien toteutumisen perusta on perustuslain 19 §:ssä. Se turvaa jokaiselleoikeuden välttämättömään toimeentuloon ja huolenpitoon", new Locale("fi")));
	}


}
