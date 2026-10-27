package com.gathin.tvhome.airplay.alac;

public class AlacFile {
    byte[] input_buffer;
    int ibIdx = 0;
    int input_buffer_bitaccumulator = 0;

    public int samplesize = 0;
    public int numchannels = 0;
    public int bytespersample = 0;

    LeadingZeros lz = new LeadingZeros();

    private final int buffer_size = 16384;

    /* buffers */
    int[] predicterror_buffer_a = new int[buffer_size];
    int[] predicterror_buffer_b = new int[buffer_size];

    int[] outputsamples_buffer_a = new int[buffer_size];
    int[] outputsamples_buffer_b = new int[buffer_size];

    int[] uncompressed_bytes_buffer_a = new int[buffer_size];
    int[] uncompressed_bytes_buffer_b = new int[buffer_size];

    /* stuff from setinfo */
    public int setinfo_max_samples_per_frame = 352;
    public int setinfo_7a = 0;
    public int setinfo_sample_size = 16;
    public int setinfo_rice_historymult = 40;
    public int setinfo_rice_initialhistory = 10;
    public int setinfo_rice_kmodifier = 14;
    public int setinfo_7f = 2;
    public int setinfo_80 = 255;
    public int setinfo_82 = 0;
    public int setinfo_86 = 0;
    public int setinfo_8a_rate = 44100;

    public int[] predictor_coef_table = new int[1024];
    public int[] predictor_coef_table_a = new int[1024];
    public int[] predictor_coef_table_b = new int[1024];
}
