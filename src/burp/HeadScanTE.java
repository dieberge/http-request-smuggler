package burp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HeadScanTE extends SmuggleScanBox implements IScannerCheck {

        HeadScanTE(String name) {
            super(name);
        }


        public boolean doConfiguredScan(byte[] original, IHttpService service, HashMap<String, Boolean> config) {
            original = setupRequest(original);
            original = Utilities.addOrReplaceHeader(original, "Accept-Encoding", "identity");
            original = Utilities.addOrReplaceHeader(original, "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36");
            original = Utilities.addCacheBuster(original, Utilities.generateCanary());
            original = Utilities.replaceFirst(original, "HTTP/2", "HTTP/1.1");
            byte[] base = Utilities.addOrReplaceHeader(original, "Transfer-Encoding", "chunked");
            base = Utilities.addOrReplaceHeader(base, "Connection", "keep-alive");
            base = Utilities.setMethod(base, "HEAD");


            HashMap<String, String> attacks = new HashMap<>();
            //attacks.put("invalid1", "FOO BAR AAH\r\n\r\n");
            //attacks.put("invalid2", "GET / HTTP/1.2\r\nFoo: bar\r\n\r\n");
            attacks.put("basic", Utilities.helpers.bytesToString(original));

            for (Map.Entry<String, String> entry: attacks.entrySet()) {
                byte[] attack = buildTEAttack(base, config, entry.getValue());
                Resp resp = request(service, attack);

                if (mixedResponse(resp)) {
                    report("Head desync TE-H2v3: "+entry.getKey(), "", resp);
                } else if (mixedResponse(resp, false)) {
                    recordCandidateFound();
                    SmuggleHelper helper = new SmuggleHelper(service);
                    helper.queue(Utilities.helpers.bytesToString(attack));
                    List<Resp> results = helper.waitFor();
                    if (mixedResponse(results.get(0), false)) {
                        report("Head desync TE-H1v8: " + entry.getKey(), "", resp, results.get(0));
                    }
//                    recordCandidateFound();
//                    Resp followup1 = request(service, Utilities.setMethod(attack, "GET"));
//                    if (!mixedResponse(followup1, false)) {
//
//                        Resp followup2 = request(service, Utilities.setMethod(attack, "FOO"));
//                        if (!mixedResponse(followup2, false)) {
//                            report("Head desync TE-H1v2: " + entry.getKey(), "", resp, followup1, followup2);
//                        }
//                    }
                }
            }

            return false;
        }

        private byte[] buildTEAttack(byte[] base, HashMap<String, Boolean> config, String attack) {
            try {

                //new ChunkContentScan("xyz").DualChunkTE().

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                //outputStream.write(makeChunked(base, attack.length(), 0, config, false));
                //putputStream.write(attack.getBytes());
//                return outputStream.toByteArray();
                outputStream.write(base);
                outputStream.write(attack.getBytes());
                return makeChunked(outputStream.toByteArray(), 0, 0, config, false);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        static boolean mixedResponse(Resp resp) {
            return mixedResponse(resp, true);
        }

        static boolean mixedResponse(Resp resp, boolean requireHTTP2) {
            if (!Utilities.containsBytes(Utilities.getBody(resp.getReq().getResponse()).getBytes(), "HTTP/1".getBytes())) {
                return false;
            }

            if (requireHTTP2) {
                if (!Utilities.containsBytes(resp.getReq().getResponse(), "HTTP/2 ".getBytes())) {
                    return false;
                }
            } else {

                // todo could use connection: close in first request?

                // if the response is chunked, burp will rewrite using content-length
                // if there's no content-length then burp will truncate randomly based on packet size
                // no longer required thanks to turbo
//                if ("".equals(Utilities.getHeader(resp.getReq().getResponse(), "Content-Length"))) {
//                    return false;
//                }

                byte[] nestedResp = Utilities.getBodyBytes(resp.getReq().getResponse());
                if (Utilities.containsBytes(nestedResp, ": chunked\r\n".getBytes())) {
                    if (Utilities.containsBytes(nestedResp, "\r\n0\r\n".getBytes())) {
                        return false;
                    }
                } else {
                    // todo misses if the second response has no length
                    try {
                        int nestedCL = Integer.parseInt(Utilities.getHeader(nestedResp, "Content-Length"));
                        int realLength = nestedResp.length - Utilities.getBodyStart(nestedResp);
                        if (realLength+10 >= nestedCL) { // fixme +10 is workaround for 'real' not counting trailing whitespace
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }

                }

            }


            return true;
        }
}

