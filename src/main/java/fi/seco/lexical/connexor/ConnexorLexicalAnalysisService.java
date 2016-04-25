package fi.seco.lexical.connexor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import com.dyuproject.protostuff.LinkedBuffer;
import com.dyuproject.protostuff.ProtostuffIOUtil;

import fi.seco.lexical.ALexicalAnalysisService;
import fi.seco.lexical.LexicalAnalysisUtil;
import fi.seco.lexical.connexor.model.ConnexorRequest;
import fi.seco.lexical.connexor.model.LANGResponse;
import fi.seco.lexical.connexor.model.MMDResponse;
import fi.seco.lexical.connexor.model.MPTResponse;

public class ConnexorLexicalAnalysisService extends ALexicalAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(ConnexorLexicalAnalysisService.class);

	private final String host;

	private final List<Locale> supportedSummarizeLocales = new ArrayList<Locale>();
	private final List<Locale> supportedBaseformLocales = new ArrayList<Locale>();
	private final List<Locale> supportedAnnotationLocales = new ArrayList<Locale>();

	private enum Service {
		FDG, MSUM
	}

	private static class Key {
		Service s;
		Locale lang;

		public Key(Service s, Locale lang) {
			this.s = s;
			this.lang = lang;
		}

		@Override
		public boolean equals(Object other) {
			return ((Key) other).s == s && ((Key) other).lang.equals(lang);
		}

		@Override
		public int hashCode() {
			return s.hashCode() + 31 * lang.hashCode();
		}
	}

	private static final int MPTPort = 52013;
	private static final int MMDPort = 52014;

	private static ObjectIntMap<Key> services = new ObjectIntHashMap<Key>();
	static {
		services.put(new Key(Service.FDG, new Locale("fi")), 52001);
		services.put(new Key(Service.FDG, new Locale("sv")), 52002);
		services.put(new Key(Service.FDG, new Locale("en")), 52003);
		services.put(new Key(Service.MSUM, new Locale("fi")), 52010);
		services.put(new Key(Service.MSUM, new Locale("sv")), 52011);
		services.put(new Key(Service.MSUM, new Locale("en")), 52012);
	}
	private final IntObjectMap<ConcurrentLinkedQueue<Socket>> sockets = new IntObjectHashMap<ConcurrentLinkedQueue<Socket>>();

	private final static Pattern p = Pattern.compile("<lemma>(.*)</lemma>");

	@Override
	public String baseform(String str, Locale lang, boolean partition, boolean guessUnknown, int maxEditDistance) {
		if (lang == null || str.length() == 0) return str;
		/*MPTResponse res = analyzeMPT(str, lang);
		StringBuilder sb = new StringBuilder();
		for (MPTResponse.Token t : res.getTokenList()) {
			sb.append(t.getReadingList().get(0).getBaseform().replaceAll(" ", ""));
			sb.append(' ');
		}
		sb.setLength(sb.length() - 1);
		return sb.toString();*/
		StringBuilder sb = new StringBuilder();
		String analyzed = analyzeFDG(LexicalAnalysisUtil.normalize(str), lang);
		if (analyzed == null) return str;
		Matcher m = p.matcher(analyzed);
		while (m.find()) {
			if (!partition) sb.append(m.group(1).replaceAll("#", ""));
			else sb.append(m.group(1));
			sb.append(' ');
		}
		return sb.toString().trim();
	}

	private Socket ensureSocket(int port) throws IOException {
		ConcurrentLinkedQueue<Socket> clqs = sockets.get(port);
		if (clqs == null) synchronized (sockets) {
			clqs = sockets.get(port);
			if (clqs == null) {
				clqs = new ConcurrentLinkedQueue<Socket>();
				sockets.put(port, clqs);
			}
		}
		Socket s = clqs.poll();
		s = null;
		if (s == null || !s.isBound() || !s.isConnected() || s.isInputShutdown() || s.isOutputShutdown() || s.isClosed()) {
			s = null;
			int tries = 30;
			IOException le = null;
			while (s == null && tries-- > 0)
				try {
					s = new Socket();
					s.connect(new InetSocketAddress(host, port), 100);
				} catch (IOException e) {
					le = e;
					s = null;
				}
			if (s == null) throw le;
		}
		return s;
	}

	private void releaseSocket(int port, Socket s) {
		sockets.get(port).offer(s);
	}

	private String run(Service s, String str, Locale lang, String encoding, boolean mmdBug) {
		int port = services.get(new Key(s, lang));
		if (port == 0) {
			log.warn("Unknown lang: " + lang);
			return str;
		}
		Socket socket;
		try {
			socket = ensureSocket(port);
		} catch (IOException e) {
			throw new RuntimeException("Couldn't connect to " + host + ":" + port, e);
		}
		String ret = run(str, socket, encoding);
		releaseSocket(port, socket);
		return ret;
	}

	public MPTResponse analyzeMPT(String str, Locale lang) {
		Socket socket;
		try {
			socket = ensureSocket(MPTPort);
		} catch (IOException e) {
			throw new RuntimeException("Couldn't connect to " + host + ":" + MPTPort, e);
		}
		ConnexorRequest rq = new ConnexorRequest();
		rq.setType(ConnexorRequest.Type.MPT);
		rq.setText(str);
		rq.setLocale(lang.toString());
		try {
			ProtostuffIOUtil.writeDelimitedTo(socket.getOutputStream(), rq, ConnexorRequest.getSchema(), LinkedBuffer.allocate(1024));
			MPTResponse lr = new MPTResponse();
			ProtostuffIOUtil.mergeDelimitedFrom(socket.getInputStream(), lr, MPTResponse.getSchema());
			if (lr.getError() != null) throw new RuntimeException(lr.getError());
			return lr;
		} catch (IOException e) {
			throw new RuntimeException("Couldn't transmit to/from " + host + ":" + MPTPort, e);
		} finally {
			releaseSocket(MPTPort, socket);
		}
	}

	public Locale guessLanguage(String str) {
		Socket socket;
		try {
			socket = ensureSocket(MPTPort);
		} catch (IOException e) {
			throw new RuntimeException("Couldn't connect to " + host + ":" + MPTPort, e);
		}
		ConnexorRequest rq = new ConnexorRequest();
		rq.setType(ConnexorRequest.Type.LANG);
		rq.setText(str);
		try {
			ProtostuffIOUtil.writeDelimitedTo(socket.getOutputStream(), rq, ConnexorRequest.getSchema(), LinkedBuffer.allocate(1024));
			LANGResponse lr = new LANGResponse();
			ProtostuffIOUtil.mergeDelimitedFrom(socket.getInputStream(), lr, LANGResponse.getSchema());
			return new Locale(lr.getLang());
		} catch (IOException e) {
			throw new RuntimeException("Couldn't transmit to/from " + host + ":" + MPTPort, e);
		} finally {
			releaseSocket(MPTPort, socket);
		}

	}

	public String analyzeFDG(String str, Locale lang) {
		String analysis = run(Service.FDG, str, lang, "ISO-8859-1", false);
		return analysis;
	}

	public MMDResponse analyzeMMD(String str, Locale lang) {
		Socket socket;
		try {
			socket = ensureSocket(MMDPort);
		} catch (IOException e) {
			throw new RuntimeException("Couldn't connect to " + host + ":" + MMDPort, e);
		}
		ConnexorRequest rq = new ConnexorRequest();
		rq.setType(ConnexorRequest.Type.MMD);
		rq.setText(str);
		rq.setLocale(lang.toString());
		try {
			ProtostuffIOUtil.writeDelimitedTo(socket.getOutputStream(), rq, ConnexorRequest.getSchema(), LinkedBuffer.allocate(1024));
			MMDResponse lr = new MMDResponse();
			ProtostuffIOUtil.mergeDelimitedFrom(socket.getInputStream(), lr, MMDResponse.getSchema());
			if (lr.getError() != null) throw new RuntimeException(lr.getError());
			return lr;
		} catch (IOException e) {
			throw new RuntimeException("Couldn't transmit to/from " + host + ":" + MMDPort, e);
		} finally {
			releaseSocket(MMDPort, socket);
		}

	}

	private String run(String str, Socket fdg, String encoding) {
		try {
			if ("".equals(str)) return str;
			StringBuilder sb = new StringBuilder();
			BufferedReader in = new BufferedReader(new InputStreamReader(fdg.getInputStream(), encoding));
			PrintWriter out = new PrintWriter(new OutputStreamWriter(fdg.getOutputStream(), encoding));
			out.write(str);
			if (str.charAt(str.length() - 1) != '\n') out.println();
			out.flush();
			String line;
			in.mark(5);
			if (in.read() != 0) {
				if (in.read() == '?' && in.read() == 'x' && in.read() == 'm' && in.read() == 'l') { // consume header on first connection
					in.readLine();
					in.readLine();
					in.read();
				} else in.reset();
				while (true) {
					line = in.readLine();
					if (line == null) throw new RuntimeException("Got null from Connexor at " + fdg);
					if (!"...".equals(line)) {
						sb.append(line);
						sb.append('\n');
						in.mark(1);
						if (in.read() == 0) {
							if (!in.ready()) break;
						} else in.reset();
					}
				}
			}
			return sb.toString();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String summarize(String str, Locale lang) {
		return run(Service.MSUM, str, lang, "UTF-8", false);
	}

	public static void main(String[] args) throws Exception {
		final ConnexorLexicalAnalysisService fdg = new ConnexorLexicalAnalysisService();
		System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
		System.out.println(fdg.baseform("Otin 007 hiusta mukaan, mutta ne menivät kuuseen foobar!@£$£‰£@$ leileipä,. z.ajxc ha dsjf,mac ,mh ", new Locale("fi"),false,false,0));
		System.out.println(fdg.baseform("Joukahaisen mierolla tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi"),false,false,0));
		System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
		System.out.println(fdg.analyzeFDG("Joukahaisen mierolla tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
		System.out.println(fdg.analyzeMPT("Joukahaisen mierolla tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
		System.out.println(fdg.analyzeMMD("Joukahaisen mierolla tiellä Lemminkäinen veti änkeröistä Antero Vipusta suunmukaisesti vartiotornissa dunkkuun, muttei saanut tätä tipahtamaan.", new Locale("fi")));
		System.out.println(fdg.analyzeMMD("Tjoo. Eli puhuin Eeron kanssa ja sain tuon Kantapuu -dumpin, joka on Suomen Museot OnLine -muodossa olevaa XML:ää, joka siis pitäisi kääntää RDF:ksi.\nLisämääreenä se, että mulla on jo valmiina julkaisuputki, jolla tämänkaltaisia asioita on tehty, ja jonka puitteissa tämäkin tehtäisiin. Putki koostuu  määritellyistä käsitteellisistä vaiheista, jotka toteutetaan erillisinä Java-ohjelmina, osaksi jaettua toiminnallisuuskirjastoa hyödyntäen. Näitä on olemassa viitisen esimerkkiä joista ottaa mallia. \nNyt ongelmana on vain se, etten ole vielä ehtinyt siistiä tai dokumentoida tuota putkea tarkemmin. Eli ensimmäiseksi mun pitäisi käyttää hieman työaikaani ympäristön valmistelemiseen sulle.  \nTule vaikka ehtiessäsi juttelemaan lisää työstä ja aikataulutuksesta ja noutamaan \nLisämääreenä se, että mulla on jo valmiina julkaisuputki, jolla tämänkaltaisia asioita on tehty, ja jonka puitteissa tämäkin tehtäisiin. Putki koostuu  määritellyistä käsitteellisistä vaiheista, jotka toteutetaan erillisinä Java-ohjelmina, osaksi jaettua toiminnallisuuskirjastoa hyödyntäen. Näitä on olemassa viitisen esimerkkiä joista ottaa mallia. ", new Locale("fi")));
		System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
		//System.out.println(fdg.summarize("Tjoo. Eli puhuin Eeron kanssa ja sain tuon Kantapuu -dumpin, joka on Suomen Museot OnLine -muodossa olevaa XML:ää, joka siis pitäisi kääntää RDF:ksi.\nLisämääreenä se, että mulla on jo valmiina julkaisuputki, jolla tämänkaltaisia asioita on tehty, ja jonka puitteissa tämäkin tehtäisiin. Putki koostuu  määritellyistä käsitteellisistä vaiheista, jotka toteutetaan erillisinä Java-ohjelmina, osaksi jaettua toiminnallisuuskirjastoa hyödyntäen. Näitä on olemassa viitisen esimerkkiä joista ottaa mallia. \nNyt ongelmana on vain se, etten ole vielä ehtinyt siistiä tai dokumentoida tuota putkea tarkemmin. Eli ensimmäiseksi mun pitäisi käyttää hieman työaikaani ympäristön valmistelemiseen sulle.  \nTule vaikka ehtiessäsi juttelemaan lisää työstä ja aikataulutuksesta ja noutamaan \nLisämääreenä se, että mulla on jo valmiina julkaisuputki, jolla tämänkaltaisia asioita on tehty, ja jonka puitteissa tämäkin tehtäisiin. Putki koostuu  määritellyistä käsitteellisistä vaiheista, jotka toteutetaan erillisinä Java-ohjelmina, osaksi jaettua toiminnallisuuskirjastoa hyödyntäen. Näitä on olemassa viitisen esimerkkiä joista ottaa mallia. ", new Locale("fi")));
	}

	@Override
	public Collection<Locale> getSupportedBaseformLocales() {
		return supportedBaseformLocales;
	}

	public Collection<Locale> getSupportedAnnotationLocales() {
		return supportedAnnotationLocales;
	}

	@Override
	public Collection<Locale> getSupportedSummarizeLocales() {
		return supportedSummarizeLocales;
	}

	public ConnexorLexicalAnalysisService(String host) {
		this.host = host;
		Locale[] toTest = new Locale[] { new Locale("fi"), new Locale("sv"), new Locale("en") };
		try {
			ensureSocket(MPTPort);
			supportedBaseformLocales.addAll(Arrays.asList(toTest));
		} catch (IOException le) {
			log.warn("mpt not available at " + host + ":" + MPTPort + ", " + le.getMessage());
		}
		try {
			ensureSocket(MMDPort);
			supportedAnnotationLocales.addAll(Arrays.asList(toTest));
		} catch (IOException le) {
			log.warn("mpt not available at " + host + ":" + MPTPort + ", " + le.getMessage());
		}
		for (Locale l : toTest) {
			int port = services.get(new Key(Service.MSUM, l));
			try {
				ensureSocket(port);
				supportedSummarizeLocales.add(l);
			} catch (IOException le) {
				log.warn(l + "_msum not available at " + host + ":" + port + ", " + le.getMessage());
			}
		}
	}

	public ConnexorLexicalAnalysisService() {
		this("nipo.seco.hut.fi");
	}

}
