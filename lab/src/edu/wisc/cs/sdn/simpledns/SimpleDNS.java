package edu.wisc.cs.sdn.simpledns;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

import edu.wisc.cs.sdn.simpledns.packet.*;

public class SimpleDNS {
    static class Server {

        private static class EC2 {
            String location;
            int subnet;
            int ip;
            String m;
            String raw;
            public EC2(int ipv4, int subnet, String location, String m, String raw) {
                this.location = location;
                this.subnet = subnet;
                this.ip = ipv4;
                this.m = m;
                this.raw = raw;
            }
        }

        private final InetAddress rootNSAddr;
        private final ArrayList<EC2> eclist;

        public Server(String root, String file) throws UnknownHostException {
            this.rootNSAddr = InetAddress.getByName(root);
            eclist = new ArrayList<EC2>();
            BufferedReader scanner;
            try {
                scanner = new BufferedReader(new FileReader(file));
                while (scanner.ready()) {
                    String line = scanner.readLine();
                    String[] tmp = line.split(",");
                    String[] tmp2 = tmp[0].split("/");
                    String location = tmp[1];
                    int ip = ByteBuffer.wrap(InetAddress.getByName(tmp2[0]).getAddress()).getInt();
                    short mask = Short.parseShort(tmp2[1]);
                    int subnetMask = 0xffffffff ^ (1 << 32 - mask) - 1;
                    eclist.add(new EC2(ip, subnetMask, location,tmp2[1],tmp2[0]));
                }
                scanner.close();
            } catch (Exception e) {
            }
        }

        public void run() throws IOException {

            InetAddress addr;
            int port;

            DatagramSocket sock;
            sock = new DatagramSocket(8053);

            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            for (;;) {
                sock.receive(packet);
                port = packet.getPort();
                addr = packet.getAddress();
                DNS dnsPacket = DNS.deserialize(packet.getData(), packet.getLength());
                if (dnsPacket.isQuery()) {
                    if (dnsPacket.getOpcode() == DNS.OPCODE_STANDARD_QUERY) {
                        List<DNSQuestion> qList = dnsPacket.getQuestions();
                        for (DNSQuestion query : qList) {
                            if(dnsPacket.isRecursionDesired()){
                                DNS respond = resolve(sock, query, dnsPacket.isRecursionDesired());
                                respond.setId(dnsPacket.getId());
                                respond.setQuestions(dnsPacket.getQuestions());
                                
                                byte[] responded = respond.serialize();
                                DatagramPacket rest = new DatagramPacket(responded, responded.length);
                                rest = optimize(rest, packet, query.getName(), query.getType());
                                rest.setPort(port);
                                rest.setAddress(addr);
                                sock.send(rest);
                            }else{
                                System.out.println("NOT recu");
                                DatagramPacket respond = nonRecursive(sock, packet, this.rootNSAddr);
                                respond = optimize(respond, packet, query.getName(), query.getType());
                                respond.setPort(port);
                                respond.setAddress(addr);
                                sock.send(respond);
                            }
                            
                            
                        }
                    }
                }
            }

        }
        private DatagramPacket optimize(DatagramPacket packet, DatagramPacket query, String origQ, short type) {
            byte[] buffer = null;       
            DNS answer = DNS.deserialize(packet.getData(), packet.getLength());
            DNSQuestion org = new DNSQuestion(origQ, type);
            List<DNSQuestion> question = new ArrayList<DNSQuestion>();
            question.add(org);
            answer.setQuestions(question);
            List<DNSResourceRecord> DNSAnswers = answer.getAnswers();
            List<DNSResourceRecord> DNSAuth = answer.getAuthorities();
            
            List<DNSResourceRecord> updated = new ArrayList<DNSResourceRecord>();
            for (DNSResourceRecord dnsAns : DNSAnswers) {
                for (DNSResourceRecord dnsAus : DNSAuth) {
                String ansIp = dnsAns.getData().toString();
                updated.add(dnsAns);
                if (dnsAns.getType() != DNS.TYPE_A) continue;
                for(int i = 0; i < this.eclist.size(); i++) {
                    EC2 ec2 = eclist.get(i);
                    String[] example = dnsAus.getData().toString().split("\\.");
                    if (example[1].substring(0,3).equals("aws")) {
                        //System.out.println(ec2.);
                        DNSRdata Rdata = new DNSRdataString(String.format("%s-%s", ec2.location, ansIp));
                        DNSResourceRecord answer2 = new DNSResourceRecord(dnsAns.getName(), DNS.TYPE_TXT, Rdata);
                        updated.add(answer2); 
                        break;
                    }
                    }
                    break;
                }
                //break;
            }
            answer.setAnswers(updated);
            buffer = answer.serialize();
            packet = new DatagramPacket(buffer, buffer.length);
            return packet;
        }   

        private static long convert(String ip) {
            String[] array = ip.split("\\.");
            long addr = 0;
            for (int i=0; i<4; i++) {
                addr |= ( ((long)Integer.parseInt(array[i])) << (24 - i * 8) );
            } 
            return addr;
        }
        private static DatagramPacket nonRecursive(DatagramSocket socket, DatagramPacket queryPkt, InetAddress dst) {
            try {
                byte[] buffer = new byte[4096];
                DatagramPacket send = new DatagramPacket(queryPkt.getData(), queryPkt.getLength(), dst, 53);
                DatagramPacket rec = new DatagramPacket(buffer, buffer.length);
                socket.send(send);
                socket.receive(rec);
                return rec;
            } catch (Exception e) {
                System.err.println(e);
            }
            return null;
        }
        
        private DNS resolve(DatagramSocket sock, DNSQuestion query, boolean rec) throws IOException {
            DNS respond = null;

            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            switch (query.getType()) { 
                case DNS.TYPE_A, DNS.TYPE_AAAA, DNS.TYPE_CNAME, DNS.TYPE_NS -> 
                {
                    DNS answer = new DNS();
                    DNSQuestion question = new DNSQuestion(query.getName(), query.getType());
                    answer.setOpcode(DNS.OPCODE_STANDARD_QUERY);
                    answer.addQuestion(question);
                    answer.setId((short) 0x00aa);
                    answer.setRecursionDesired(rec);
                    answer.setRecursionAvailable(false);
                    answer.setQuery(true);
                    byte[] answerSerialized = answer.serialize();
                    DatagramPacket qpacket = new DatagramPacket(answerSerialized, answerSerialized.length);
                    qpacket.setAddress(rootNSAddr);
                    qpacket.setPort(53);
                    sock.send(qpacket);
                    sock.receive(packet);
                    respond = DNS.deserialize(packet.getData(), packet.getLength());
                    System.out.println(respond);
                    if (!rec) {
                        return respond;
                    }
                    List<DNSResourceRecord> answers = new ArrayList<DNSResourceRecord>();
                    List<DNSResourceRecord> auth = new ArrayList<DNSResourceRecord>();
                    List<DNSResourceRecord> add = new ArrayList<DNSResourceRecord>();

                    while (respond.getRcode() == DNS.RCODE_NO_ERROR) {
                        if (!respond.getAnswers().isEmpty()) {
                            for (DNSResourceRecord ansRecord : respond.getAnswers()) {
                                answers.add(ansRecord);
                                if (ansRecord.getType() == DNS.TYPE_CNAME) {
                                    boolean isAns = false;
                                    for (DNSResourceRecord record : respond.getAnswers()) {
                                        String name = record.getName();
                                        String data = ((DNSRdataName) ansRecord.getData()).getName();
                                        if (name.equals(data)) {
                                            isAns = true;
                                            break;
                                        }
                                    }
                                    if (isAns) {continue;}
                                    if (query.getType() == DNS.TYPE_A || query.getType() == DNS.TYPE_AAAA) {
                                        DNSQuestion cnameQuery = new DNSQuestion(((DNSRdataName) ansRecord.getData()).getName(), query.getType());
                                        DNS resolved = resolve(sock, cnameQuery, rec);
                                        answers.addAll(resolved.getAnswers());
                                        auth = resolved.getAuthorities();
                                        add = resolved.getAdditional();
                                    }
                                }
                            }
                            break;
                        } else {
                            auth = respond.getAuthorities();
                            add = respond.getAdditional();
                            if (respond.getAuthorities().isEmpty())
                                break;
                            for (DNSResourceRecord authRec : respond.getAuthorities()) {
                                if (authRec.getType() == DNS.TYPE_NS) {
                                    DNSRdataName authStr = (DNSRdataName) authRec.getData();
                                    if (!respond.getAdditional().isEmpty()) {
                                        for (DNSResourceRecord addRecord : respond.getAdditional()) {
                                            if (authStr.getName().contentEquals(addRecord.getName())) {
                                                if (addRecord.getType() == DNS.TYPE_A) {
                                                    DNSRdataAddress addrData = (DNSRdataAddress) addRecord.getData();
                                                    qpacket.setAddress(addrData.getAddress());
                                                    sock.send(qpacket);
                                                    sock.receive(packet);
                                                    respond = DNS.deserialize(packet.getData(), packet.getLength());
                                                    System.out.println(respond);
                                                }
                                            }
                                        }
                                    } else {
                                        qpacket.setAddress(InetAddress.getByName(authStr.getName()));
                                        sock.send(qpacket);
                                        sock.receive(packet);
                                        respond = DNS.deserialize(packet.getData(), packet.getLength());
                                        System.out.println(respond);
                                    }
                                }
                            }
                        }
                    }
                    respond.setAuthorities(auth);
                    respond.setAdditional(add);
                    ArrayList<DNSResourceRecord> ec2rec = new ArrayList<DNSResourceRecord>();
                    for (DNSResourceRecord record : answers) {
                        if (record.getType() == DNS.TYPE_A) {
                            DNSRdataAddress address = (DNSRdataAddress) (record.getData());
                            String EC2location = this.lookup(address.getAddress());
                            if (EC2location != null) {
                                DNSRdata text = new DNSRdataString(EC2location + "-" + address.getAddress().getHostAddress());
                                DNSResourceRecord ECrecord = new DNSResourceRecord(record.getName(), (short) 16, text);
                                ec2rec.add(ECrecord);
                            }
                        }
                    }

                    answers.addAll(ec2rec);

                    respond.setAnswers(answers);

                }
                default -> {}
            }

            return respond;
        }

        private String lookup(InetAddress addr) {
            String s = null;
            for (EC2 ec2 : eclist) {
                int mask = ByteBuffer.wrap(addr.getAddress()).getInt() & ec2.subnet;
                int ec2addr = ec2.ip & ec2.subnet;
                if (mask == ec2addr)
                    return ec2.location;
            }
            return s;
        }
    }

    public static void main(String[] args) {
        if (args.length == 4 && args[0].contentEquals("-r") && args[2].contentEquals("-e")) {
            try {
                Server dnsserver = new Server(args[1], args[3]);
                dnsserver.run();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        else {
            System.out.println("Usage : java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
            System.exit(-1);
        }

    }
}
