package expect4j;

import expect4j.matches.Match;
import expect4j.matches.RegExpMatch;
import org.apache.oro.text.regex.MalformedPatternException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * @author Emrah Soytekin (emrah.soytekin@gmail.com)
 * <p>
 * Created on Jan, 2019
 */
public class Expect4jParser extends Expect4j{
    /**
     * Interface to the Java 2 platform's core logging facilities.
     */
    private static final Logger logger = LoggerFactory.getLogger(Expect4jParser.class);

    private static final String SEND = "send";
    private static final String EXPECT_KEYWORD = "expect";

    public Expect4jParser(IOPair pair) {
        super(pair);
    }

    public Expect4jParser(Socket socket) throws IOException {
        super(socket);
    }

    public Expect4jParser(InputStream is, OutputStream os) {
        this( new StreamPair(is, os) );
        logger.trace("Created Expect4J instance {} based on InputStream {} and OutputStream {}",this,is,os);
    }

    public Expect4jParser(Process process) {
        super(process);
    }

    public void runScript(String cmd) throws Exception {


        List<Map<String, Object>> parsedCommandList = parse(cmd);

        if(parsedCommandList.isEmpty()){
            throw new Exception("faulty script definition!");
        }

        for(Map<String,Object> m: parsedCommandList){
            if(m.get(EXPECT_KEYWORD) == null && m.get(SEND)!=null) {
                List<String> sendCommandList = (List<String>) m.get(SEND);
                for(String sendCommand: sendCommandList){
                    sendCommand(null, sendCommand);
                }
            }else if (m.get(EXPECT_KEYWORD) instanceof String){
                processExpectStatement(m);
            } else if (m.get(EXPECT_KEYWORD) instanceof List){
                List<Match> exp = (List<Match>) m.get(EXPECT_KEYWORD);
                expect(exp);
                List<String> sendCommandList = (List<String>) m.get(SEND);
                if(sendCommandList!= null){
                    for(String sendCommand: sendCommandList){
                        sendCommand(null, sendCommand);
                    }
                }

            }

            if(getLastState().getMatch() == null){
                logger.error("last state is null");
            }
        }

    }

    private void processExpectStatement(Map<String, Object> m) throws Exception {
        final String expCmd = (String) m.get(EXPECT_KEYWORD);
        final List<String> sendCommandList = (List<String>) m.get(SEND);

        expect(expCmd, new Closure() {
            @Override
            public void run(ExpectState expectState) throws Exception {
                logger.info("found a match with buffer \n{} ", expectState.getBuffer());
                logger.info("last match \n{}", expectState.getMatch());

                if(sendCommandList != null && !sendCommandList.isEmpty()){
                    for(String sendCommand: sendCommandList){
                        sendCommand(expectState, sendCommand);
                    }
                }

            }
        });
    }

    private List<Map<String, Object>> parse(String script){
        String[] l = script.split("\n");

        List<Map<String, Object>> mapList = new ArrayList<>();
        List<String> commandList = new ArrayList<String>();
        Map<String,Object> expMap = new HashMap<String, Object>();
        boolean isIf = false;
        for(int i=0; i<l.length; i++){
            String c = l[i];

            if(c.matches("^\\s*#.*$")){
                continue;
            }
            if(c.contains(EXPECT_KEYWORD)){

                if(!commandList.isEmpty()){
                    expMap.put(SEND, commandList);
                    mapList.add(expMap);
                    expMap = new HashMap<String, Object>();
                    commandList = new ArrayList<String>();
                }

                Matcher m = java.util.regex.Pattern.compile("expect \"(.*)\".*").matcher(c);
                if (m.find()) {
                    String expCmd = m.group(1);


                    expMap = new HashMap<String, Object>();
                    expMap.put(EXPECT_KEYWORD, expCmd);

                } else if (c.matches("expect \\{.*")) {
                    isIf = true;
                }



            } else {

                if(isIf){
                    String endIfPattern = ".*}.*";
                    List<Match> matchList = new ArrayList<Match>();
                    while(!c.matches(endIfPattern)){
                        String ifPattern = "\\s*\"(.*)\".*\\{\\s*";
                        String sendPattern = ".*send\\s*\"(.*)\"\\s*";
                        Matcher m = java.util.regex.Pattern.compile(ifPattern).matcher(c);
                        if(m.find()){
                            String expression = m.group(1);

                            c = l[++i];
                            final List<String> matchCommandList = new ArrayList<String>();
                            while(!c.matches(endIfPattern)){
                                Matcher m2 = java.util.regex.Pattern.compile(sendPattern).matcher(c);
                                if (m2.find()) {
                                    String sendExpresson = m2.group(1);
                                    matchCommandList.add(sendExpresson.replaceAll("\\\\r","\r" ));

                                } else {
                                    matchCommandList.add(c.trim());
                                }

                                c = l[++i];
                            }

                            Match match = getMatch(expression, matchCommandList);
                            if(match != null){
                                matchList.add(match);
                            }
                        }

                        c = l[++i];
                    }

                    expMap.put(EXPECT_KEYWORD, matchList);
                    mapList.add(expMap);
                    isIf = false;

                } else {
                    Matcher m = java.util.regex.Pattern.compile("send \"(.*)\".*").matcher(c);
                    if(m.find()){
                        String cmd = m.group(1);
                        commandList.add(cmd.replaceAll("\\\\r","\r" ));
                    } else {
                        if(c.trim().length()>0)
                        {
                            commandList.add(c.trim());
                        }
                    }

                }


            }
        }

        if(!commandList.isEmpty()){
            expMap.put(SEND, commandList);
        }

        if(!expMap.isEmpty() && !mapList.contains(expMap)){
            mapList.add(expMap);
        }

        return mapList;
    }

    private Match getMatch(String expression, final List<String> matchCommandList)  {
        try {
            return new RegExpMatch(expression, new Closure() {
                @Override
                public void run(ExpectState expectState) throws Exception {
                    for (String s : matchCommandList) {
                        sendCommand(expectState, s);
                    }

                }
            });
        } catch (MalformedPatternException e) {
            logger.error("Error", e);
        }

        return null;
    }

    private void sendCommand(ExpectState expectState, String s) throws Exception {
        logger.debug("sending command \"{}\"", s);
        if (s.matches("exit 1(\\s+.*)?")) {
            logger.warn("terminating script with exit code 1");
            String[] ts = s.split("exit 1");
            if (ts.length > 1) {
                String t = ts[1].trim();
                String errorMessage = t.replaceAll("\"", "");
                throw new Exception(errorMessage);
            } else {
                throw new Exception("bad situation. terminating!");
            }


        } else if (s.equalsIgnoreCase("exit")) {
            logger.info("script successful. terminating");

        } else if (s.contains("sleep")) {
            String t = s.split(" ")[1];
            Thread.sleep(Long.valueOf(t));
        } else if (s.contains("exp_continue")) {
            if(expectState != null){
                expectState.exp_continue();
            } else {
                getLastState().exp_continue();
            }
        } else {
            send(s);
        }
    }
}
