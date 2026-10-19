package com.localcaption.instant;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Local OPUS-MT SentencePiece Unigram tokenizer. It reads the original source.spm,
 * including its normalization table, rather than the converted tokenizer.json:
 * that file mixes source and target scores and omits the normalization table.
 *
 * The protobuf reader, Viterbi search and Darts table reader are self-contained;
 * no native tokenizer library, Google service or network is used at runtime.
 * Normalization follows the Apache-2.0 SentencePiece specification and algorithms:
 * https://github.com/google/sentencepiece/blob/master/src/normalizer.cc
 */
final class MarianTokenizer {
    static final int EOS_ID = 0;
    static final int UNK_ID = 1;
    static final int PAD_ID = 65000;
    static final int MAX_SOURCE_TOKENS = 512;

    final int eosId,unkId,padId;

    private final Trie root = new Trie();
    private final String[] idToPiece;
    private final Map<String, Integer> pieceToId;
    private final int[] normalizationTrie;
    private final byte[] normalizedStrings;
    private final float unknownScore;
    private final boolean addDummyPrefix;
    private final boolean removeExtraWhitespaces;
    private final boolean escapeWhitespaces;

    /** directory contains source.spm and vocab.json; a file in that directory is also accepted. */
    MarianTokenizer(File directory) throws IOException {
        File dir = directory.isDirectory() ? directory : directory.getParentFile();
        try {
            JSONObject vocab = new JSONObject(new String(Files.readAllBytes(new File(dir, "vocab.json").toPath()), StandardCharsets.UTF_8));
            idToPiece = new String[vocab.length()];
            pieceToId = new HashMap<>(vocab.length() * 2);
            for (Iterator<String> it = vocab.keys(); it.hasNext();) {
                String piece = it.next();
                int id = vocab.getInt(piece);
                if (id < 0 || id >= idToPiece.length || idToPiece[id] != null)
                    throw new IOException("Invalid Marian vocabulary ID: " + id);
                idToPiece[id] = piece;
                pieceToId.put(piece, id);
            }
            Integer eos=pieceToId.get("</s>"),unk=pieceToId.get("<unk>"),pad=pieceToId.get("<pad>");
            if(eos==null||unk==null||pad==null||eos.equals(unk)||eos.equals(pad)||unk.equals(pad))
                throw new IOException("Missing or conflicting Marian special tokens");
            eosId=eos;unkId=unk;padId=pad;

            Proto model = new Proto(Files.readAllBytes(new File(dir, "source.spm").toPath()));
            byte[] normalizer = null;
            float minScore = Float.POSITIVE_INFINITY;
            int normalPieces = 0;
            while (model.next()) {
                if (model.field == 1) {
                    Proto p = new Proto(model.bytes());
                    String piece = null;
                    float score = 0;
                    int type = 1;
                    while (p.next()) {
                        if (p.field == 1) piece = p.text();
                        else if (p.field == 2) score = p.float32();
                        else if (p.field == 3) type = (int) p.number();
                    }
                    if (type == 1) {
                        if (piece == null || piece.isEmpty()) throw new IOException("Empty SentencePiece");
                        // A few source pieces are absent from the merged Marian vocabulary;
                        // the reference tokenizer retains their score and maps their output to UNK.
                        int id = pieceToId.getOrDefault(piece, unkId);
                        Trie node = root;
                        for (int i = 0; i < piece.length(); i++)
                            node = node.children.computeIfAbsent(piece.charAt(i), key -> new Trie());
                        node.id = id;
                        node.score = score;
                        minScore = Math.min(minScore, score);
                        normalPieces++;
                    } else if (type != 2 && type != 3 && type != 5) {
                        throw new IOException("Unsupported SentencePiece type: " + type);
                    }
                } else if (model.field == 2) {
                    Proto trainer=new Proto(model.bytes());
                    while(trainer.next())if(trainer.field==3&&trainer.number()!=1)
                        throw new IOException("Only SentencePiece Unigram models are supported");
                } else if (model.field == 3) normalizer = model.bytes();
            }
            if (normalPieces < 100 || normalizer == null) throw new IOException("Incomplete SentencePiece model");
            unknownScore = minScore - 10.0f;
            boolean prefix = true, remove = true, escape = true;
            byte[] charsMap = null;
            Proto norm = new Proto(normalizer);
            while (norm.next()) {
                if (norm.field == 2) charsMap = norm.bytes();
                else if (norm.field == 3) prefix = norm.number() != 0;
                else if (norm.field == 4) remove = norm.number() != 0;
                else if (norm.field == 5) escape = norm.number() != 0;
            }
            addDummyPrefix = prefix;
            removeExtraWhitespaces = remove;
            escapeWhitespaces = escape;
            if (charsMap == null || charsMap.length < 8) throw new IOException("Missing SentencePiece normalization table");
            int trieBytes = littleInt(charsMap, 0);
            if (trieBytes < 1024 || (trieBytes & 1023) != 0 || trieBytes >= charsMap.length - 4)
                throw new IOException("Invalid SentencePiece normalization table");
            normalizationTrie = new int[trieBytes / 4];
            for (int i = 0; i < normalizationTrie.length; i++) normalizationTrie[i] = littleInt(charsMap, 4 + i * 4);
            normalizedStrings = Arrays.copyOfRange(charsMap, 4 + trieBytes, charsMap.length);
            if (normalizedStrings[normalizedStrings.length - 1] != 0) throw new IOException("Invalid normalization strings");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot read Marian tokenizer", e);
        }
    }

    int tokenId(String piece) { return pieceToId.getOrDefault(piece, unkId); }
    int vocabularySize(){return idToPiece.length;}

    /** Encodes source text and appends EOS; an initial supported >>language<< tag is kept as one token. */
    long[] encode(String text) {
        text = text == null ? "" : text;
        int languageId = -1;
        if (text.startsWith(">>")) {
            int end = text.indexOf("<<", 2);
            if (end >= 0) {
                Integer id = pieceToId.get(text.substring(0, end + 2));
                if (id != null) {
                    languageId = id;
                    text = text.substring(end + 2);
                }
            }
        }
        String normalized = normalize(text);
        int size = normalized.length();
        float[] scores = new float[size + 1];
        Arrays.fill(scores, Float.NEGATIVE_INFINITY);
        scores[0] = 0;
        int[] starts = new int[size + 1];
        int[] ids = new int[size + 1];
        boolean[] unknown = new boolean[size + 1];
        Arrays.fill(starts, -1);
        for (int start = 0; start < size;) {
            int charLength = Character.charCount(normalized.codePointAt(start));
            if (scores[start] != Float.NEGATIVE_INFINITY) {
                Trie node = root;
                boolean hasSingleCharacter = false;
                for (int end = start; end < size; end++) {
                    node = node.children.get(normalized.charAt(end));
                    if (node == null) break;
                    if (node.id >= 0) {
                        // SentencePiece 0.2.x compares the normal-piece sum in
                        // double precision before storing its float path score.
                        // Keeping that order also preserves its tie decisions.
                        double candidate = (double) scores[start] + node.score;
                        if (candidate > scores[end + 1]) {
                            scores[end + 1] = (float) candidate;
                            starts[end + 1] = start;
                            ids[end + 1] = node.id;
                            unknown[end + 1] = false;
                        }
                        if (end + 1 - start == charLength) hasSingleCharacter = true;
                    }
                }
                if (!hasSingleCharacter) {
                    int end = start + charLength;
                    float candidate = scores[start] + unknownScore;
                    if (candidate > scores[end]) {
                        scores[end] = candidate;
                        starts[end] = start;
                        ids[end] = unkId;
                        unknown[end] = true;
                    }
                }
            }
            start += charLength;
        }
        ArrayList<Integer> reversed = new ArrayList<>();
        for (int end = size; end > 0;) {
            if (starts[end] < 0) throw new IllegalStateException("No SentencePiece path");
            if (unknown[end]) {
                // SentencePiece returns the literal unknown span. Marian then maps
                // that text through the shared vocabulary, where it may be known.
                int spanEnd = end;
                do { end = starts[end]; } while (end > 0 && unknown[end]);
                reversed.add(pieceToId.getOrDefault(normalized.substring(end, spanEnd), unkId));
            } else {
                reversed.add(ids[end]);
                end = starts[end];
            }
        }
        Collections.reverse(reversed);
        int prefix = languageId >= 0 ? 1 : 0;
        long[] result = new long[reversed.size() + prefix + 1];
        if (prefix == 1) result[0] = languageId;
        for (int i = 0; i < reversed.size(); i++) result[i + prefix] = reversed.get(i);
        result[result.length - 1] = eosId;
        return result;
    }

    String decode(List<Integer> ids) {
        StringBuilder out = new StringBuilder();
        for (int id : ids) appendPiece(out, id);
        return unescape(out);
    }

    String decode(int[] ids) {
        StringBuilder out = new StringBuilder();
        for (int id : ids) appendPiece(out, id);
        return unescape(out);
    }

    private void appendPiece(StringBuilder out, int id) {
        if (id == eosId || id == unkId || id == padId) return;
        if (id < 0 || id >= idToPiece.length) throw new IllegalArgumentException("Invalid Marian token: " + id);
        String piece = idToPiece[id];
        if (piece.startsWith(">>") && piece.endsWith("<<")) return;
        out.append(piece);
    }

    private static String unescape(StringBuilder out) {
        String text = out.toString().replace('\u2581', ' ');
        return text.startsWith(" ") ? text.substring(1) : text;
    }

    /** Uses the model's precompiled nmt_nfkc map, including compatibility and whitespace rules. */
    String normalize(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream mapped = new ByteArrayOutputStream(input.length);
        for (int pos = 0; pos < input.length;) {
            int node = offset(normalizationTrie[0]);
            int longest = 0, value = -1;
            for (int i = pos; i < input.length; i++) {
                node ^= input[i] & 255;
                if (node < 0 || node >= normalizationTrie.length) break;
                int unit = normalizationTrie[node];
                if ((unit & 0x800000ff) != (input[i] & 255)) break;
                node ^= offset(unit);
                if (node < 0 || node >= normalizationTrie.length) break;
                if ((unit & 256) != 0) {
                    longest = i - pos + 1;
                    value = normalizationTrie[node] & 0x7fffffff;
                }
            }
            if (longest > 0 && value >= 0 && value < normalizedStrings.length) {
                int end = value;
                while (end < normalizedStrings.length && normalizedStrings[end] != 0) end++;
                mapped.write(normalizedStrings, value, end - value);
                pos += longest;
            } else {
                int lead = input[pos] & 255;
                int length = lead < 128 ? 1 : lead < 224 ? 2 : lead < 240 ? 3 : 4;
                length = Math.min(length, input.length - pos);
                mapped.write(input, pos, length);
                pos += length;
            }
        }
        String raw = new String(mapped.toByteArray(), StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(raw.length() + 1);
        char space = escapeWhitespaces ? '\u2581' : ' ';
        boolean previousSpace = removeExtraWhitespaces;
        if (addDummyPrefix && !raw.isEmpty()) result.append(space);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ') {
                if (!removeExtraWhitespaces || !previousSpace) result.append(space);
                previousSpace = true;
            } else {
                result.append(c);
                previousSpace = false;
            }
        }
        if (removeExtraWhitespaces)
            while (result.length() > 0 && result.charAt(result.length() - 1) == space) result.setLength(result.length() - 1);
        return result.toString();
    }

    private static int offset(int unit) { return (unit >>> 10) << ((unit & 512) >>> 6); }
    private static int littleInt(byte[] b, int i) {
        return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16) | ((b[i + 3] & 255) << 24);
    }

    private static final class Trie {
        final HashMap<Character, Trie> children = new HashMap<>();
        int id = -1;
        float score;
    }

    /** Bounded protobuf wire reader; only the model fields needed for inference are decoded. */
    private static final class Proto {
        final byte[] data;
        int position, field, wire, start, end;
        long value;
        Proto(byte[] data) { this.data = data; }
        boolean next() throws IOException {
            if (position == data.length) return false;
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            if (field == 0) throw new IOException("Invalid protobuf field");
            if (wire == 0) { value = varint(); start = end = position; }
            else if (wire == 2) {
                long length = varint();
                if (length < 0 || length > data.length - position) throw new IOException("Invalid protobuf length");
                start = position; position += (int) length; end = position;
            } else if (wire == 1 || wire == 5) {
                int length = wire == 1 ? 8 : 4;
                if (length > data.length - position) throw new IOException("Truncated protobuf");
                start = position; position += length; end = position;
            } else throw new IOException("Unsupported protobuf wire type");
            return true;
        }
        long varint() throws IOException {
            long n = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position == data.length) throw new IOException("Truncated protobuf integer");
                int v = data[position++] & 255;
                n |= (long) (v & 127) << shift;
                if (v < 128) return n;
            }
            throw new IOException("Invalid protobuf integer");
        }
        byte[] bytes() throws IOException {
            if (wire != 2) throw new IOException("Expected protobuf bytes");
            return Arrays.copyOfRange(data, start, end);
        }
        String text() throws IOException { return new String(bytes(), StandardCharsets.UTF_8); }
        long number() throws IOException {
            if (wire != 0) throw new IOException("Expected protobuf integer");
            return value;
        }
        float float32() throws IOException {
            if (wire != 5) throw new IOException("Expected protobuf float");
            return Float.intBitsToFloat(littleInt(data, start));
        }
    }
}
