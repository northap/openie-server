all: OpenIEServer.class

openie = ./openie-5.0.jar

OpenIEServer.class: OpenIEServer.java
	javac -cp $(openie) OpenIEServer.java

clean:
	rm -f *.class
