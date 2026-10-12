class CompanyProfile {
  final String? name;
  final String? address;
  final String? phone;
  final String? email;

  /// Только для чтения (бланк «Объём и масса»): склад как в «Реализации» 1С и кладовщик.
  final String? warehouseName;
  final String? warehouseManagerName;

  /// Для рекламной страницы на главной сайта: часы выдачи, «о нас», юр. лицо и БИН.
  final String? pickupHours;
  final String? about;
  final String? legalInfo;
  final String? instagramUrl;
  final String? whatsappPhone;
  final String? tiktokUrl;

  /// Для страниц сайта /contacts и /vacancies.
  final String? workHours;
  final String? jobsPhone;
  final String? mapPoint;

  CompanyProfile({this.name, this.address, this.phone, this.email, this.warehouseName, this.warehouseManagerName,
      this.pickupHours, this.about, this.legalInfo, this.instagramUrl, this.whatsappPhone, this.tiktokUrl,
      this.workHours, this.jobsPhone, this.mapPoint});

  factory CompanyProfile.fromJson(Map<String, dynamic> json) => CompanyProfile(
        name: json['name'] as String?,
        address: json['address'] as String?,
        phone: json['phone'] as String?,
        email: json['email'] as String?,
        warehouseName: json['warehouseName'] as String?,
        warehouseManagerName: json['warehouseManagerName'] as String?,
        pickupHours: json['pickupHours'] as String?,
        about: json['about'] as String?,
        legalInfo: json['legalInfo'] as String?,
        instagramUrl: json['instagramUrl'] as String?,
        whatsappPhone: json['whatsappPhone'] as String?,
        tiktokUrl: json['tiktokUrl'] as String?,
        workHours: json['workHours'] as String?,
        jobsPhone: json['jobsPhone'] as String?,
        mapPoint: json['mapPoint'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'name': name,
        'address': address,
        'phone': phone,
        'email': email,
        'pickupHours': pickupHours,
        'about': about,
        'legalInfo': legalInfo,
        'instagramUrl': instagramUrl,
        'whatsappPhone': whatsappPhone,
        'tiktokUrl': tiktokUrl,
        'workHours': workHours,
        'jobsPhone': jobsPhone,
        'mapPoint': mapPoint,
      };
}
