package uz.yukgo.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Haydovchining mashinasi haqida ma'lumot (hammaga ko'rinadi). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class Vehicle {
    @Column(name = "vehicle_brand")
    private String brand;

    @Column(name = "vehicle_model")
    private String model;

    @Column(name = "vehicle_plate")
    private String plateNumber;

    @Column(name = "vehicle_color")
    private String color;

    @Column(name = "vehicle_year")
    private Integer year;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_body_type")
    private BodyType bodyType;

    @Column(name = "vehicle_capacity_kg")
    private Integer capacityKg;

    @Column(name = "vehicle_volume_m3")
    private Double volumeM3;

    @Column(name = "vehicle_photo_url")
    private String photoUrl;

    public boolean isPresent() {
        return brand != null && !brand.isBlank();
    }
}
