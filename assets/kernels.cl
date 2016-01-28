__kernel void mandel(__global unsigned short* output)           	
{                                               	
    int x_dim = get_global_id(0);                      	
	int y_dim = get_global_id(1);                      	
    size_t w = get_global_size(0);                     	
    //size_t h = get_global_size(1);                     	
    int index = y_dim*w + x_dim;                       	
    float x_seed = (x_dim/(float)w)*2.5f - 2;          	
    float y_seed = (y_dim/(float)w)*2 - 1;     		   	
    float x = 0.0f;					                   	
    float y = 0.0f;				                       	
	int iteration = 0;	                               		
    int max = 4095;				                       	
    do {					                           	
       iteration++;				                   	
       if (iteration > max) break;					   	
       float xtemp = x;						       	
       x = x*x - y*y + x_seed;          			   		
       y = 2*xtemp*y + y_seed;				   		   	
       }						   					   	
    while (x*x + y*y <= 4);						       	
	//printf(\"\\n%d, %d\", index, iteration);			
	output[index] = (iteration > max)?0:iteration;     	
}                                                  	
