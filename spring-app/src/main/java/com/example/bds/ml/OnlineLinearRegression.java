package com.example.bds.ml;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

import java.util.ArrayList;
import java.util.List;

public class OnlineLinearRegression {
    private final List<double[]> X = new ArrayList<>();
    private final List<Double> y = new ArrayList<>();
    private volatile double[] beta; // null until trained

    public synchronized void add(double[] xNoBias, double label) {
        double[] row = new double[xNoBias.length + 1];
        row[0] = 1.0;
        System.arraycopy(xNoBias, 0, row, 1, xNoBias.length);
        X.add(row); y.add(label);
    }

    public synchronized void refitIf(int stride) {
        if (X.isEmpty()) return;
        if (X.size() % Math.max(1, stride) != 0) return;
        int n = X.size(), p = X.get(0).length;
        double[][] Xa = new double[n][p]; double[] ya = new double[n];
        for (int i=0;i<n;i++){ Xa[i]=X.get(i); ya[i]=y.get(i); }
        RealMatrix Xm = new Array2DRowRealMatrix(Xa, false);
        RealVector yv = new ArrayRealVector(ya, false);
        RealVector b  = new QRDecomposition(Xm).getSolver().solve(yv);
        beta = b.toArray();
    }

    public double predict(double[] xNoBias) {
        double[] b = beta;
        if (b == null) return baseline(xNoBias);
        double s = b[0];
        for (int i=0;i<xNoBias.length;i++) s += b[i+1]*xNoBias[i];
        return s;
    }

    public boolean trained(){ return beta != null; }
    public double[] coefficients(){ return beta; }
    public int samples(){ return X.size(); }

    private static double baseline(double[] x){
        double size = x.length>0?x[0]:1.0, pages=x.length>1?x[1]:1.0;
        return Math.max(64.0, 128.0 + 2.5*size + 0.5*pages);
    }
}
