package com.mnc.ausy.domain;
import jakarta.persistence.*;
@Entity @Table(name="web_sequence")
public class WebSequence {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="next_number") private Long nextNumber = 1L;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getNextNumber(){return nextNumber;} public void setNextNumber(Long v){nextNumber=v;}
}
