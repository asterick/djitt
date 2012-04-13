public class ProcessState {
	public int a,b,c,x,y,z,i,j;
	public int pc;
	public int sp;
	public int o;
	public int[] ram;

	public int read(int address) {
		return ram[address];
	}
	
	public void write(int data, int address) {
		ram[address] = data;
	}
}
