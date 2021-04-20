JC = javac
JFLAGS = -g
.SUFFIXES: .java .class
.java.class:
	$(JC) $(JFLAGS) $*.java

CLASSES = \
	Peer.java 
default: classes

classes: $(CLASSES:.java=.class)

clean:
	$(RM) *.class