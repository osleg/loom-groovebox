/* SPDX-License-Identifier: GPL-3.0-only */
import com.groovebox.cafa.*;
import java.util.*;
import java.nio.file.*;

public final class CafaProtocolTest {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static Protocol.Frame decode(byte[] value) {
        final Protocol.Frame[] frame = {null};
        Protocol.Decoder d = new Protocol.Decoder(f -> frame[0] = f);
        for (byte b : value) d.feed(new byte[]{b}, 0, 1);
        return frame[0];
    }
    static class Host implements CafaSession.Host {
        List<Protocol.Frame> sent = new ArrayList<>(); int applied, resets, stopped, inputs;
        public void send(byte[] bytes) { sent.add(decode(bytes)); }
        public byte[] snapshot() { return state(7, 3); }
        public boolean input(int serial, byte[] bytes) { inputs++; return serial == 1 && bytes.length == 22; }
        public void reset() { resets++; }
        public void applied() { applied++; }
        public void stopped(String reason) { stopped++; }
    }
    static byte[] state(int track, int page) {
        byte[] b = new byte[156]; b[0]=(byte)track; b[1]=(byte)page;
        Protocol.put14(b,3,64); Protocol.put14(b,5,48); Protocol.put14(b,7,63); Protocol.put14(b,9,120);
        Protocol.text(b,20,16,"LOOM T"+(track+1)); Protocol.text(b,36,16,"SUBTRACTIVE");
        String[] labels={"ATK %","DEC %","SUS %","REL %"};
        for(int i=0;i<4;i++) { Protocol.text(b,52+i*8,8,labels[i]); Protocol.put14(b,84+i*2,i*33); }
        return b;
    }
    public static void main(String[] args) throws Exception {
        for(int track=0;track<8;track++) for(int page=0;page<9;page++) {
            byte[] data=state(track,page); Protocol.Frame f=decode(Protocol.frame(5,3,57,16383,data));
            check(f.version==5 && f.session==57 && f.serial==16383 && Arrays.equals(data,f.payload),"framing");
        }
        final int[] calls={0}; Protocol.Decoder d=new Protocol.Decoder(f->calls[0]++);
        byte[] f=Protocol.frame(5,1,33,0,new byte[0]);
        d.feed(f,0,5); d.feed(new byte[]{(byte)0xf8},0,1); d.feed(f,5,f.length-5); check(calls[0]==1,"realtime interleave");
        byte[] tooLong=new byte[400]; tooLong[0]=(byte)0xf0; tooLong[399]=(byte)0xf7; d.feed(tooLong,0,tooLong.length); check(calls[0]==1,"overflow");
        d.feed(f,0,f.length); check(calls[0]==2,"recover after overflow");
        Host host=new Host(); CafaSession link=new CafaSession(host); link.begin(33,0);
        check(host.sent.size()==1 && !link.isApplied(),"hello not applied");
        link.receive(decode(Protocol.frame(2,2,33,0,new byte[]{1})),10); link.tick(250);
        check(host.sent.get(1).command==Protocol.HELLO,"wrong version ignored");
        link.receive(decode(Protocol.frame(5,2,33,0,new byte[]{1})),260); link.tick(300);
        check(host.sent.get(2).command==Protocol.STATE && !link.isApplied(),"state sent but not applied");
        link.receive(decode(Protocol.frame(5,2,33,1,new byte[]{1})),310);
        check(link.isApplied() && host.applied==1,"applied ack gates audio");
        for(int t=344;t<1700;t+=34) { link.tick(t); link.receive(decode(Protocol.frame(5,2,33,1,new byte[]{1})),t); }
        link.tick(1810); check(!link.isOpen() && host.stopped==1,"replayed ACK cannot renew lease");
        host=new Host(); link=new CafaSession(host); link.begin(44,0);
        link.receive(decode(Protocol.frame(5,2,44,0,new byte[]{1})),1); link.tick(34);
        link.receive(decode(Protocol.frame(5,6,44,2,new byte[22])),40);
        check(!link.isOpen() && host.stopped==1,"input gap closes session");
        host=new Host(); link=new CafaSession(host); link.begin(45,0); link.receive(decode(Protocol.frame(5,2,45,0,new byte[]{1})),1);
        for(int i=1;i<=17000;i++) { long now=i*34L; link.tick(now); link.receive(decode(Protocol.frame(5,2,45,i&16383,new byte[]{1})),now+1); check(link.isOpen(),"revision wrap"); }
        check(host.applied==1,"one applied event per session"); link.close(); check(!link.isOpen(),"close");
        if(args.length>0) {
            Path p=Paths.get(args[0]); Files.createDirectories(p);
            Files.write(p.resolve("hello.syx"),Protocol.frame(5,1,57,0,new byte[0]));
            Files.write(p.resolve("track7-state.syx"),Protocol.frame(5,3,57,1,state(6,1)));
            Files.write(p.resolve("track8-env.syx"),Protocol.frame(5,3,57,2,state(7,3)));
            Files.write(p.resolve("track8-env.bin"),state(7,3));
            Files.write(p.resolve("bye.syx"),Protocol.frame(5,5,57,2,new byte[0]));
        }
        System.out.println("CAFA protocol/session: "+checks+" assertions passed");
    }
}
